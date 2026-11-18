package io.github.decodede.potatoflix.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import kotlin.uuid.toJavaUuid
import io.github.decodede.potatoflix.App
import io.github.decodede.potatoflix.Engine
import io.github.decodede.potatoflix.Library
import io.github.decodede.potatoflix.LinkEvent
import io.github.decodede.potatoflix.Playable
import io.github.decodede.potatoflix.Settings
import io.github.decodede.potatoflix.SkipTimes
import io.github.decodede.potatoflix.WatchEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Zoom modes for the picture (Fit keeps the aspect ratio, Fill crops, Zoom crops more). */
enum class Zoom(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
}

/** The shape of the last video decoded, remembered across "next episode" so the screen doesn't flip back first. */
object LastVideoOrientation {
    @Volatile var vertical: Boolean? = null
}

/** One selectable track (embedded, or a subtitle file added from a link), for the Audio/Subtitles menus. */
data class TrackOption(
    /** Stable key: "audio-<index>-<label>"/"ext-<hash>" for files, "emb-<index>-<label>" for embedded text. */
    val key: String,
    val label: String,
    val detail: String?,
    val selected: Boolean,
)

/**
 * One playback: finds sources (streamed in as the extension reports them), starts the best one,
 * falls back to the next source when one fails, and records progress for Continue Watching.
 * State is Compose state, so the screen simply reads it.
 */
class PlayerController(
    val api: MainAPI,
    val response: LoadResponse,
    val playable: Playable,
    chooseSource: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    private val trackSelector = DefaultTrackSelector(App.context)

    /** Buffering suited to this device's RAM (Settings → Player → Buffer, -1 = auto). */
    private val loadControl = DeviceTier.bufferDurationsMs.let { d ->
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(d[0].toInt(), d[1].toInt(), d[2].toInt(), d[3].toInt())
            .setTargetBufferBytes(DeviceTier.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, true)
            .build()
    }

    /** Software (FFmpeg) decoding of codecs this hardware can't do, on phones/tablets; hardware first. */
    private val renderersFactory = run {
        val mode = Settings.softwareDecoding // -1 auto, 0 HW+SW, 1 HW only, 2 SW+HW
        val (enableSoftware, preferSoftware) = when (mode) {
            0 -> true to false
            2 -> true to true
            1 -> false to false
            // Auto: FFmpeg as a safety net everywhere; switchable in Settings → Player → Codec.
            else -> true to false
        }
        if (enableSoftware) FixedNextRenderersFactory(App.context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(
                if (preferSoftware) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
        } else DefaultRenderersFactory(App.context).setEnableDecoderFallback(true)
    }

    var player: ExoPlayer = buildPlayer()
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            main.post {
                when (playbackState) {
                    Player.STATE_BUFFERING -> buffering = true
                    Player.STATE_READY -> {
                        buffering = false
                        if (!started) {
                            started = true
                            watchdog?.cancel()
                            onPlaying()
                        }
                        val d = player.duration
                        if (d > 0 && d != C.TIME_UNSET && length != d) { length = d; loadSegments() }
                    }
                    Player.STATE_ENDED -> { buffering = false; onEndReached() }
                    Player.STATE_IDLE -> Unit
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            main.post {
                playing = isPlaying
                if (isPlaying) trakr?.playing(percent()) else trakr?.paused(percent())
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val chain = generateSequence(error as Throwable?) { it.cause }.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            io.github.decodede.potatoflix.AppLog.e("player", "error: $chain")
            main.post { failover() }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            main.post {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    vertical = videoSize.height > videoSize.width
                    LastVideoOrientation.vertical = vertical
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            currentTracks = tracks
            applyTextSelection()
            applyAudioSelection()
            if (!languagesApplied) {
                languagesApplied = true
                main.postDelayed({ applyLanguages() }, 800)
            }
        }
    }

    /** What the player had the last time tracks changed: the source of the Audio/Subtitles menus. */
    private var currentTracks by mutableStateOf(Tracks.EMPTY)
    private var audioKey: String? = null
    private var textKey: String? = null   // null = nothing forced; external ids start with "ext-"
    private var textDisabled = false

    @OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(App.context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters(300_000, 300_000))
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    .setFallbackMinPlaybackSpeed(0.97f)
                    .build()
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

    val links = mutableStateListOf<ExtractorLink>()
    val subtitles = mutableStateListOf<SubtitleFile>()
    var searching by mutableStateOf(true)
    var searchError by mutableStateOf<String?>(null)
    var current by mutableStateOf<ExtractorLink?>(null)
    var started by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var zoom by mutableStateOf(runCatching { Zoom.valueOf(Settings.playerZoom) }.getOrDefault(Zoom.FIT))
    var selectedSubtitle by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** Set when a stream that answers fine still doesn't play: what to send the developer (a problem in the app). */
    var appIssue by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false)
    /** The video's dimensions (0 until the first frame is sized): drives vertical auto-rotation. */
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    /** True when the video is taller than it is wide (vertical video). */
    var vertical by mutableStateOf(false)
        private set

    /** Auto-play waits for this; "Choose source" turns it off. */
    var autoPlay = Settings.autoPlay && !chooseSource
        private set

    private val tried = mutableSetOf<String>()
    private var startAt: Long = 0
    private var watchdog: Job? = null
    private var saver: Job? = null
    val isLive = response is LiveStreamLoadResponse

    private var languagesApplied = false
    private var videoReady = false
    private var playCalledAt = 0L

    /** Intro/recap/credits times (anime, from AniSkip) and the one the playhead is in, if any. */
    var segments by mutableStateOf<List<SkipTimes.Segment>>(emptyList())
    var activeSegment by mutableStateOf<SkipTimes.Segment?>(null)
    private var segmentsRequested = false
    private val autoSkipped = mutableSetOf<SkipTimes.Segment>()

    val playables = Engine.playables(response)
    val next: Playable? = playables.indexOfFirst { it.data == playable.data && it.dub == playable.dub }
        .takeIf { it >= 0 }?.let { playables.getOrNull(it + 1) }

    init {
        player.addListener(listener)
        if (!isLiveNow()) {
            Library.progress(api.name, playable.data)?.let { p ->
                if (p.duration > 0 && p.position / p.duration in 0.02..0.92) startAt = (p.position * 1000).toLong()
            }
        }
        scope.launch { findSources() }
        saver = scope.launch {
            var tick = 0
            while (true) {
                delay(500)
                // Skip buttons need responsive time updates; the heavy Save/WeTrakr work runs every 10 s.
                val pos = player.currentPosition
                if (pos >= 0) { time = pos; updateSkip(pos) }
                val d = player.duration
                if (d > 0 && d != C.TIME_UNSET && length != d) { length = d; loadSegments() }
                if (++tick >= 20) { tick = 0; if (playing) { save(); trakr?.tick(percent()) } }
            }
        }
    }

    private fun onPlaying() {
        current?.let { io.github.decodede.potatoflix.LastSource.remember(api.name, response.url, it) }
        trakr?.playing(percent())
        applyLevelAudio()
    }

    /** Subtitle sync in milliseconds (positive = later). Only files can be shifted. */
    var subtitleDelay by mutableLongStateOf(0L)
        private set
    fun syncSubtitles(ms: Long) { subtitleDelay = ms.coerceIn(-600_000, 600_000); reloadSubtitle() }

    /** External subtitle files exactly as picked (not the cleaned/shifted cache copy), for the menus. */
    private val externalSubtitles = mutableListOf<SubtitleFile>()
    /** url+lang → the resolved (cleaned/sync-shifted) copy the player plays. */
    private val resolvedConfigs = mutableMapOf<String, SubtitleFile>()
    /** url+lang → the config id that is current for the current sync/clean settings. */
    private var externalConfigIds = mutableMapOf<String, String>()

    private fun externalKey(s: SubtitleFile) = s.url + "\n" + s.lang

    /** The config id changes when the subtitle sync or the clean-up changes, so stale tracks are never re-shown. */
    private fun externalId(s: SubtitleFile) =
        "ext-" + Integer.toHexString((externalKey(s) + "\n" + subtitleDelay + "\n" + SubtitleStyle.currentClean().key).hashCode())

    private fun externalConfig(s: SubtitleFile, id: String) =
        androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
            if (s.url.startsWith("/")) android.net.Uri.fromFile(java.io.File(s.url)) else android.net.Uri.parse(s.url)
        )
            .setMimeType(subtitleMime(s.url))
            .setLanguage(s.lang)
            .setId(id)
            .setSelectionFlags(0) // picked by the user, or by Settings → Player → subtitle language
            .build()

    private fun subtitleMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> "application/x-subrip"
        }
    }

    private data class TextRef(val label: String?, val language: String?, val id: String?, val mime: String?, val group: TrackGroup, val index: Int)

    private fun textTrackRefs(): List<TextRef> = currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { g -> (0 until g.length).map { i -> TextRef(g.getTrackFormat(i).label, g.getTrackFormat(i).language, g.getTrackFormat(i).id, g.getTrackFormat(i).sampleMimeType, g.getMediaTrackGroup(), i) } }

    private fun audioRefs(): List<TextRef> = currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { g -> (0 until g.length).map { i -> TextRef(g.getTrackFormat(i).label, g.getTrackFormat(i).language, g.getTrackFormat(i).id, g.getTrackFormat(i).sampleMimeType, g.getMediaTrackGroup(), i) } }

    private fun trackKey(r: TextRef) = r.id?.takeIf { it.isNotBlank() } ?: listOfNotNull(r.label, r.language).joinToString("|").ifBlank { "track-${r.index}" }

    private fun applyTextSelection() {
        val params = trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textDisabled).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        io.github.decodede.potatoflix.AppLog.i("tracks", "text: ${textTrackRefs().joinToString { "${it.id}='${it.label}'/${it.language}" }} -> $textKey${if (textDisabled) " (disabled)" else ""}")
        val key = textKey
        if (!textDisabled && key != null) {
            val match = textTrackRefs().firstOrNull { trackKey(it) == key || it.id == key }
            if (match != null) params.setOverrideForType(TrackSelectionOverride(match.group, match.index))
        }
        runCatching { trackSelector.parameters = params.build() }
    }

    private fun applyAudioSelection() {
        val params = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        val key = audioKey
        if (key != null) {
            val match = audioRefs().firstOrNull { trackKey(it) == key }
            if (match != null) params.setOverrideForType(TrackSelectionOverride(match.group, match.index))
        }
        runCatching { trackSelector.parameters = params.build() }
    }

    /** Selectable audio tracks (embedded), for the Audio menu. */
    fun audioOptions(): List<TrackOption> = audioRefs().map { r ->
        TrackOption(trackKey(r), r.label ?: r.language?.uppercase() ?: "Audio ${r.index + 1}",
            r.mime?.removePrefix("audio/")?.uppercase(), audioKey == trackKey(r))
    }

    /** Subtitle files from the links not added to the player yet. */
    fun pendingSubtitles(): List<SubtitleFile> = subtitles.filter { s -> externalSubtitles.none { it.url == s.url } }

    /** Selectable text tracks: embedded ones, then the links' subtitle files. */
    fun subtitleOptions(): List<TrackOption> {
        val embedded = textTrackRefs().filter { it.id?.startsWith("ext-") != true }.map { r ->
            TrackOption(trackKey(r), r.label ?: r.language?.uppercase() ?: "Subtitle ${r.index + 1}", "In the video", !textDisabled && textKey == trackKey(r))
        }
        val external = externalSubtitles.map { s ->
            val key = externalKey(s)
            externalConfigIds[key] = externalId(s)
            TrackOption(externalId(s), s.lang, "From ${api.name}", !textDisabled && textKey == externalId(s))
        }
        return (external + embedded).distinctBy { it.key }
    }

    fun selectAudio(option: TrackOption) {
        audioKey = option.key.takeUnless { it == audioKey }
        applyAudioSelection()
    }

    /** [option] null = subtitles off. */
    fun selectSubtitle(option: TrackOption?) {
        if (option == null) {
            textDisabled = true
            textKey = null
            selectedSubtitle = null
        } else {
            textDisabled = false
            textKey = option.key
            selectedSubtitle = externalSubtitles.firstOrNull { externalId(it) == option.key }?.url
        }
        applyTextSelection()
    }

    /** These sources came from the 20-minute cache (or the search started on the title page). */
    private var fromCache = false

    private var refreshed = false

    private suspend fun findSources() {
        fromCache = Engine.cached(api, playable.data)
        Engine.links(api, playable.data).collect { event ->
            when (event) {
                is LinkEvent.Link -> {
                    val link = event.link
                    // Torrents play only with torrent streaming on (Settings → Player); otherwise they're counted, to say so.
                    if (io.github.decodede.potatoflix.Torrents.isTorrent(link) && !io.github.decodede.potatoflix.Torrents.enabled) {
                        skippedTorrents++
                        return@collect
                    }
                    if (links.none { it.url == link.url }) links.add(link)
                    // The source that last played this title starts as soon as it's found (Continue Watching, Resume).
                    if (autoPlay && current == null && io.github.decodede.potatoflix.LastSource.find(api.name, response.url, listOf(link)) != null) {
                        App.toast("Continuing with ${link.name}")
                        play(link)
                    } else if (autoPlay && current == null && instantPlayable(link)) {
                        // A link the app can start at once (like the official player): don't wait for the rest.
                        play(link)
                    }
                }
                // Added to the player when picked in the Subtitles menu.
                is LinkEvent.Subtitle -> if (subtitles.none { it.url == event.subtitle.url }) subtitles.add(event.subtitle)
                is LinkEvent.Done -> {
                    searching = false
                    event.error?.let { searchError = it.message ?: it.toString() }
                    if (autoPlay && current == null) ranked().firstOrNull()?.let { play(it) }
                    if (links.isEmpty()) searchError = if (skippedTorrents > 0)
                        "Only torrent sources were found ($skippedTorrents). Turn on Settings → Player → Torrent streaming, or set the add-on " +
                            "up with a debrid service (Torrentio: torrentio.strem.fun/configure)."
                    else searchError ?: "No sources found"
                }
            }
        }
    }

    /** A link worth starting without waiting for the rest of the search (about 480p or better). */
    private fun instantPlayable(l: ExtractorLink): Boolean {
        if (io.github.decodede.potatoflix.Torrents.isTorrent(l)) return false // needs peers; wait for the full list
        return l.quality >= 480
    }

    /** Best first: closest to the preferred quality without going over it; torrents after direct links. */
    fun ranked(): List<ExtractorLink> = Engine.rank(links.toList()).sortedBy { io.github.decodede.potatoflix.Torrents.isTorrent(it) }

    /** Torrent sources left out because torrent streaming is off. */
    private var skippedTorrents = 0
    /** While a torrent connects and buffers: peers, speed, how much of the start is there. */
    var torrentStatus by mutableStateOf<io.github.decodede.potatoflix.Torrents.Status?>(null)
        private set
    private var torrentJob: Job? = null
    private var openTorrent: String? = null

    private fun closeTorrent() {
        torrentJob?.cancel()
        torrentStatus = null
        openTorrent?.let { uri -> openTorrent = null; Thread { io.github.decodede.potatoflix.Torrents.close(uri) }.start() }
    }

    /** The heights the current source offers (from the tracks), best first; empty for direct files. */
    fun qualityHeights(): List<Int> = currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).mapNotNull { i -> g.getTrackFormat(i).height.takeIf { h -> h > 0 } } }
        .distinct().sortedDescending()

    /** The quality pinned for this playback (null = adaptive/Auto). */
    var pinnedHeight by mutableStateOf<Int?>(null)
        private set

    /** Pins a quality ([height] null = automatic). Adaptive; nothing reloads. */
    fun setQualityHeight(height: Int?) {
        pinnedHeight = height
        runCatching {
            val params = trackSelector.buildUponParameters()
            if (height == null) params.clearVideoSizeConstraints() else params.setMaxVideoSize(Int.MAX_VALUE, height)
            trackSelector.parameters = params.build()
        }
    }

    private fun headersOf(link: ExtractorLink) = buildMap {
        putAll(link.headers)
        if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", link.referer)
        if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", USER_AGENT)
    }

    private fun okHttpClient(): OkHttpClient = com.lagradost.cloudstream3.app.baseClient

    /** Sideloaded audio tracks (video-only streams) merged into one program (Media3 sideloaded audio). */
    private fun mergeSeparateAudio(
        link: ExtractorLink,
        videoSource: androidx.media3.exoplayer.source.MediaSource,
        headers: Map<String, String>,
    ): androidx.media3.exoplayer.source.MediaSource {
        val separateAudio = link.audioTracks.filter { it.url.isNotBlank() }
        if (separateAudio.isEmpty()) return videoSource
        val audioSources = separateAudio.map { audio ->
            val aFactory = DefaultMediaSourceFactory(App.context).setDataSourceFactory(
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient())
                    .setDefaultRequestProperties(audio.headers ?: headers)
            )
            aFactory.createMediaSource(
                androidx.media3.common.MediaItem.Builder().setUri(audio.url).build()
            )
        }
        return audioSources.fold(videoSource) { acc, next -> MergingMediaSource(acc, next) }
    }


    /** Starts [link] ([sourceOverride]: a local loopback address for torrents), keeping the position when switching. */
    fun play(link: ExtractorLink, sourceOverride: String? = null) {
        if (sourceOverride == null && openTorrent != null && openTorrent != link.url) closeTorrent()
        // A torrent: join it and buffer its start, then play it from this device's own little server.
        if (sourceOverride == null && io.github.decodede.potatoflix.Torrents.isTorrent(link)) {
            watchdog?.cancel()
            current = link
            tried += link.url
            error = null; ended = false; buffering = true
            openTorrent = link.url
            torrentJob?.cancel()
            torrentJob = scope.launch {
                val local = runCatching { io.github.decodede.potatoflix.Torrents.open(link.url) { st -> torrentStatus = st } }
                if (current?.url != link.url) return@launch
                local.onSuccess { url -> play(link, url) }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@onFailure
                    torrentStatus = null
                    App.toast("Torrent: ${e.message}")
                    failover()
                }
            }
            return
        }
        watchdog?.cancel()
        currentSourceOverride = if (sourceOverride != null && !io.github.decodede.potatoflix.Torrents.isTorrent(link)) sourceOverride else null
        playCalledAt = android.os.SystemClock.elapsedRealtime()
        val keep = if (started) time.takeIf { it > 0 } ?: startAt else startAt
        if (sourceOverride == null && current?.url != link.url) current = link
        tried += link.url
        error = null
        ended = false
        buffering = true

        val headers = headersOf(link)
        val source = sourceOverride ?: link.url
        val drm = link as? DrmExtractorLink
        val requestHeaders = headers // Media3 sends them on every request: playlist, variants, segments, keys, subtitles
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient())
            .setDefaultRequestProperties(requestHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(App.context)
            .setDataSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(5_000)

        if (drm != null) {
            mediaSourceFactory.setDrmSessionManagerProvider(drmProvider(drm, okHttpClient()))
        }
        val item = buildMediaItem(mime = mimeTypeOf(link), source = source, drm = drm)
        var mediaSource = mediaSourceFactory.createMediaSource(item)
        // Some extractors ship the audio separately (e.g. YouTube video-only streams): merge the streams.
        mediaSource = mergeSeparateAudio(link, mediaSource, headers)

        io.github.decodede.potatoflix.CrashReport.playing("${api.name}: ${response.name} (${playable.label}); source ${link.name} [${link.type}] from " +
            "${runCatching { android.net.Uri.parse(link.url).host }.getOrNull() ?: "?"}")
        io.github.decodede.potatoflix.GlitchReport.step("Playing source ${link.name} [${link.type}] from ${runCatching { android.net.Uri.parse(link.url).host }.getOrNull() ?: "?"}")

        player.setMediaSource(mediaSource)
        player.prepare()
        if (keep > 0) player.seekTo(keep)
        player.setPlaybackSpeed(speed)
        player.playWhenReady = true
        applyTrackVolume()

        // A source that hasn't started after 25 s (dead host, endless redirect) gives way to the next one;
        // a TV channel after 12 s (it has nothing else to wait for).
        watchdog?.cancel()
        if (!started) watchdog = scope.launch {
            delay(if (TorrentsOf(link)) 60_000 else 35_000)
            if (!started && current?.url == link.url) failover()
        }
        // Subtitle files added earlier keep playing on the new source.
        rebuildSubtitlesIfNeeded()
    }

    private fun TorrentsOf(link: ExtractorLink) = io.github.decodede.potatoflix.Torrents.isTorrent(link)

    private fun buildMediaItem(mime: String?, source: String, drm: DrmExtractorLink?): androidx.media3.common.MediaItem {
        val item = androidx.media3.common.MediaItem.Builder()
            .setUri(source)
            .setMediaId(source)
        mime?.let { item.setMimeType(it) }
        if (drm != null) {
            item.setDrmConfiguration(
                androidx.media3.common.MediaItem.DrmConfiguration.Builder(drm.uuid.toJavaUuid())
                    .setLicenseUri(drm.licenseUrl)
                    .setMultiSession(drm.uuid != CLEARKEY_DRM_UUID)
                    .build()
            )
        }
        val subs = externalSubtitles.mapNotNull { s ->
            resolvedConfigs[externalKey(s)]?.let { resolved -> externalConfig(resolved, externalId(s)) }
        }
        if (subs.isNotEmpty()) item.setSubtitleConfigurations(subs)
        return item.build()
    }

    private fun mimeTypeOf(link: ExtractorLink): String? = when (link.type) {
        ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
        ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun androidx.media3.common.MediaItem.DrmConfiguration.Builder.setKeyRequestParametersPlaceholder() = this

    /** DRM: clearkey from an embedded key, or Widevine against a licence URL (like the official player). */
    private fun drmProvider(drm: DrmExtractorLink, client: OkHttpClient): DrmSessionManagerProvider = DrmSessionManagerProvider { _ ->
        val uuid = drm.uuid.toJavaUuid()
        val builder = DefaultDrmSessionManager.Builder()
            .setPlayClearSamplesWithoutKeys(true)
            .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
        if (drm.uuid == CLEARKEY_DRM_UUID) {
            // The key travels with the link (kid/key): no licence server involved.
            val keyJson = """{"keys":[{"kty":"${drm.kty ?: "oct"}","k":"${drm.key}","kid":"${drm.kid}"}],"type":"temporary"}""".toByteArray()
            builder.setMultiSession(false).setKeyRequestParameters(drm.keyRequestParameters)
            builder.build(LocalMediaDrmCallback(keyJson))
        } else {
            builder.setMultiSession(true)
            val licenseDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
            builder.build(HttpMediaDrmCallback(drm.licenseUrl ?: "", licenseDataSourceFactory))
        }
    }

    /** Volume in percent (0–100). */
    private fun applyTrackVolume() {
        player.volume = (Settings.playerVolume.coerceIn(0, 200) / 100f).coerceAtMost(1f)
    }

    /** Live toggle from the Audio menu: applies (or clears) the loudness enhancer without reloading. */
    fun refreshLevelAudio() = applyLevelAudio()

    /** Applies (again) a LoudnessEnhancer when "even out loud and quiet sounds" is on. */
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private fun applyLevelAudio() {
        loudness?.release()
        loudness = null
        if (!Settings.levelAudio) return
        runCatching {
            android.media.audiofx.LoudnessEnhancer(player.audioSessionId).apply {
                setTargetGain(800) // +8 dB: dialogue up, explosions down
                enabled = true
            }
        }.getOrNull()?.let { loudness = it }
    }

    /** Shows an already added subtitle file again (a new source starts without it). */
    fun selectFile(file: java.io.File) {
        selectedSubtitle = file.absolutePath
        scope.launch {
            val sub = runCatching { com.lagradost.cloudstream3.newSubtitleFile(file.nameWithoutExtension, file.absolutePath) }.getOrNull() ?: return@launch
            addSubtitle(sub)
        }
    }

    /** Adds a local subtitle file to the player: cleaned when it is SRT/VTT. */
    fun addSubtitleFile(file: java.io.File, name: String = file.name) {
        addedFiles += file to name
        selectedSubtitle = file.absolutePath
        scope.launch {
            val sub = runCatching { com.lagradost.cloudstream3.newSubtitleFile(name, file.absolutePath) }.getOrNull() ?: return@launch
            addSubtitle(sub)
        }
    }

    /** Subtitle files added during this playback (online, translated), with their names. */
    val addedFiles = mutableStateListOf<Pair<java.io.File, String>>()
    var translating by mutableStateOf<String?>(null)
        private set

    /**
     * Makes [s] the active subtitle. The file is cleaned (captions/ads/capitals) and shifted by the subtitle
     * sync first; the cleaned copy is cached. Rebuilding keeps the playhead.
     */
    fun addSubtitle(s: SubtitleFile) {
        val key = externalKey(s)
        // Already the active external subtitle: only re-apply the selection.
        if (textKey == externalId(s) && !textDisabled && resolvedConfigs.containsKey(key)) {
            applyTextSelection()
            return
        }
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { prepareExternalSubtitle(s, SubtitleStyle.currentClean()) }
                    .onFailure { io.github.decodede.potatoflix.AppLog.w("Potatoflix", "subtitle clean-up failed", it) }
                    .getOrNull()
            } ?: s
            if (externalSubtitles.none { externalKey(it) == key }) externalSubtitles += s
            resolvedConfigs[key] = resolved
            externalConfigIds[key] = externalId(s)
            selectedSubtitle = s.url
            textKey = externalId(s)
            textDisabled = false
            if (textTrackRefs().any { it.id == textKey }) applyTextSelection() else rebuild()
        }
    }

    /** The subtitle sync changed: re-resolve the selected file (new cache entry) and reload it. */
    fun reloadSubtitle() {
        val url = selectedSubtitle ?: return
        val orig = externalSubtitles.firstOrNull { it.url == url } ?: return
        addSubtitle(orig)
    }

    /** [s] cleaned (and shifted by the subtitle sync), cached; remote files only (local files pass through). */
    private suspend fun prepareExternalSubtitle(s: SubtitleFile, clean: SubtitleStyle.Clean): SubtitleFile {
        val delay = subtitleDelay
        if (!clean.active && delay == 0L && s.url.startsWith("/")) return s
        val dir = java.io.File(App.context.cacheDir, "subtitles").apply { mkdirs() }
        val key = "d-${s.url.hashCode().toUInt()}-${clean.key}-$delay."
        dir.listFiles()?.firstOrNull { it.name.startsWith(key) && it.length() > 0 }
            ?.let { return com.lagradost.cloudstream3.newSubtitleFile(s.lang, it.absolutePath) }
        val bytes = when {
            s.url.startsWith("/") -> java.io.File(s.url).readBytes()
            s.url.startsWith("file:") -> java.io.File(android.net.Uri.parse(s.url).path!!).readBytes()
            else -> {
                val h = s.headers?.takeIf { it.isNotEmpty() }
                    ?: current?.referer?.takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) }
                    ?: emptyMap()
                val r = com.lagradost.cloudstream3.app.get(s.url, headers = h, timeout = 20)
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                r.body.bytes()
            }
        }
        val text = SubtitleStyle.decode(bytes)
        if (text.isBlank()) return s
        val isAss = Regex("""(?im)^\s*\[script info]|^\s*Dialogue[: ]""").containsMatchIn(text)
        if (isAss) {
            // ASS/SSA: ExoPlayer's SSA parser renders it (positions, colours, bold/italic) — keep as-is.
            return com.lagradost.cloudstream3.newSubtitleFile(s.lang, java.io.File(dir, "${key}ssa").apply { writeText(text) }.absolutePath)
        }
        if (!text.contains("-->")) return s // binary (PGS/DVB): nothing the app can parse yet
        var out = if (clean.active) SubtitleStyle.clean(text, clean) else text
        if (delay != 0L) out = SubtitleStyle.shift(out, delay)
        val ext = if (out.trimStart().startsWith("WEBVTT")) "vtt" else "srt"
        return com.lagradost.cloudstream3.newSubtitleFile(s.lang, java.io.File(dir, key + ext).apply { writeText(out) }.absolutePath)
    }

    /** Subtitle files for this playback are in the media item (resolved copies); just re-apply the selection. */
    private fun rebuildSubtitlesIfNeeded() = applyTextSelection()

    /** Rebuilds the current source with the current subtitle configuration (position kept). */
    private fun rebuild() {
        val link = current ?: return
        val keep = player.currentPosition.takeIf { it > 0 } ?: startAt
        val wasPlaying = player.playWhenReady
        // play() keeps the same URL: beside the media source, set it again with the new configs.
        val mime = mimeTypeOf(link)
        val headers = headersOf(link)
        val drm = link as? DrmExtractorLink
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient())
            .setDefaultRequestProperties(headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(App.context).setDataSourceFactory(dataSourceFactory).setLiveTargetOffsetMs(5_000)
        if (drm != null) mediaSourceFactory.setDrmSessionManagerProvider(drmProvider(drm, okHttpClient()))
        val item = buildMediaItem(mime, link.url, drm)
        player.setMediaSource(mergeSeparateAudio(link, mediaSourceFactory.createMediaSource(item), headers))
        player.prepare()
        if (keep > 0) player.seekTo(keep)
        player.playWhenReady = wasPlaying
        applyTrackVolume()
    }

    private fun matchesWanted(label: String?, code: String?, wanted: List<String>): Boolean {
        val text = listOfNotNull(label, code).joinToString(" ").lowercase()
        return wanted.any { w -> Engine.languageVariants(w).any { v -> v.isNotBlank() && text.contains(v) } }
    }

    /** Picks the audio and subtitle languages from Settings → Player, once per playback. */
    private fun applyLanguages() {
        val audio = Settings.audioLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (audio.isNotEmpty()) audioRefs().firstOrNull { r -> matchesWanted(r.label, r.language, audio) }
            ?.let { r -> audioKey = trackKey(r); applyAudioSelection() }
        val subs = Settings.subtitleLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (subs.isEmpty() || selectedSubtitle != null) return
        val embedded = textTrackRefs().firstOrNull { r -> matchesWanted(r.label, r.language, subs) }
        if (embedded != null) { textKey = trackKey(embedded); applyTextSelection(); return }
        // An external subtitle in the right language: added by the Subtitles flow; pick it if it arrives.
        subtitles.firstOrNull { s -> subs.any { w -> io.github.decodede.potatoflix.OnlineSubtitles.matches(s.lang, w) || Engine.languageVariants(w).any { v -> s.lang.lowercase().contains(v) } } }?.let { s ->
            selectedSubtitle = s.url
            addSubtitle(s)
        }
    }

    private fun trackLabel(r: TextRef) = listOfNotNull(r.label, r.language).joinToString(" ")

    /** What "Translate subtitles" starts from: the subtitles showing, else the first ones on offer. */
    fun translateSourceName(): String? {
        val sel = selectedSubtitle
        addedFiles.firstOrNull { it.first.absolutePath == sel }?.let { return it.second }
        subtitles.firstOrNull { it.url == sel }?.let { return it.lang }
        return subtitles.firstOrNull()?.lang ?: addedFiles.firstOrNull()?.second
    }

    private suspend fun translateSourceFile(): java.io.File? = withContext(Dispatchers.IO) {
        val sel = selectedSubtitle
        addedFiles.firstOrNull { it.first.absolutePath == sel }?.let { return@withContext it.first }
        val sub = subtitles.firstOrNull { it.url == sel } ?: subtitles.firstOrNull()
            ?: return@withContext addedFiles.firstOrNull()?.first
        if (sub.url.startsWith("file:")) return@withContext java.io.File(android.net.Uri.parse(sub.url).path!!)
        if (sub.url.startsWith("/")) return@withContext java.io.File(sub.url)
        val dir = java.io.File(App.context.cacheDir, "subtitles").apply { mkdirs() }
        val file = java.io.File(dir, "x-${sub.url.hashCode().toUInt()}.sub")
        if (!file.isFile || file.length() == 0L) {
            val headers = sub.headers ?: listOfNotNull(current?.referer?.takeIf { it.isNotBlank() }?.let { "Referer" to it }).toMap()
            val r = com.lagradost.cloudstream3.app.get(sub.url, headers = headers, timeout = 20)
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            file.writeBytes(r.body.bytes())
        }
        file
    }

    /** Translates the current subtitles into [lang] and switches to them. */
    fun translateSubtitles(lang: String) {
        val name = io.github.decodede.potatoflix.SubtitleTranslator.languageName(lang)
        val from = translateSourceName() ?: return
        translating = name
        App.toast("Translating subtitles to $name…")
        scope.launch {
            try {
                val src = translateSourceFile() ?: throw java.io.IOException("No subtitles to translate")
                val out = io.github.decodede.potatoflix.SubtitleTranslator.translateFile(src, lang, java.io.File(App.context.cacheDir, "subtitles"))
                addSubtitleFile(out, "$from → $name")
                App.toast("Subtitles: $name (translated)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                App.toast("Couldn't translate: ${t.message ?: t}")
            } finally {
                translating = null
            }
        }
    }

    private fun loadSegments() {
        if (segmentsRequested || isLive || length <= 0 || !SkipTimes.applies(response)) return
        if (!Settings.skipButtons && !Settings.autoSkip && !Settings.autoPlayNext) return
        segmentsRequested = true
        scope.launch { segments = runCatching { SkipTimes.forEpisode(response, playable.episode, length) }.getOrDefault(emptyList()) }
    }

    private fun updateSkip(t: Long) {
        val seg = segments.firstOrNull { t >= it.start && t < it.end - 1000 }
        if (seg != null && Settings.autoSkip && seg !in autoSkipped) {
            autoSkipped += seg
            seekTo(seg.end)
            activeSegment = null
            return
        }
        activeSegment = seg?.takeIf { Settings.skipButtons }
    }

    fun skipSegment() { activeSegment?.let { seekTo(it.end) }; activeSegment = null }

    /** Where the next-episode countdown starts: at the credits if known, else 20 s before the end. */
    fun countdownStart(): Long? {
        if (next == null || isLive || length < 5 * 60_000) return null
        val credits = segments.firstOrNull { it.kind == SkipTimes.Kind.CREDITS && it.start > length / 2 && it.start < length - 5000 }
        return credits?.start ?: (length - 20_000)
    }

    /** The one API between the screen and the player: everything goes through Media3 on its main looper. */
    fun togglePause() { if (playing) player.pause() else player.play() }
    fun pause() = player.pause()
    fun resume() = player.play()

    fun seekBy(ms: Long) = seekTo((player.currentPosition + ms).coerceIn(0, length.takeIf { it > 0 } ?: Long.MAX_VALUE))

    fun seekTo(ms: Long) {
        time = ms
        player.seekTo(ms)
    }

    fun setRate(rate: Float) {
        speed = rate
        player.setPlaybackSpeed(rate)
    }

    /** The resize button: Fit → Fill → Zoom → Fit (the others stay in Settings → Player). */
    fun cycleZoom(): Zoom {
        val next = when (zoom) { Zoom.FIT -> Zoom.FILL; Zoom.FILL -> Zoom.ZOOM; else -> Zoom.FIT }
        setZoomMode(next)
        return next
    }

    /** Reopens the current source where it is (after changing something that applies when a source opens). */
    fun reopen() { current?.let { play(it, currentSourceOverride) } }
    private var currentSourceOverride: String? = null

    fun setZoomMode(z: Zoom) {
        zoom = z
        Settings.playerZoom = z.name
    }

    /** Subtitle files shown for this playback. */
    fun addOnlineSubtitle(r: io.github.decodede.potatoflix.OnlineSubtitles.Result) {
        App.toast("Downloading ${r.languageName} subtitles…")
        scope.launch {
            runCatching { io.github.decodede.potatoflix.OnlineSubtitles.download(r) }
                .onSuccess { addSubtitleFile(it, r.languageName) }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) App.toast("Couldn't download the subtitles: ${e.message}") }
        }
    }

    private var released = false

    private fun onEndReached() {
        // Ignore a late event from the media that was just replaced.
        if (android.os.SystemClock.elapsedRealtime() - playCalledAt < 5_000 || player.playbackState == Player.STATE_IDLE) return
        val len = length
        val t = time
        playing = false
        // Some hosts send a short "error" clip instead of failing; a real episode is longer than 3 minutes.
        if (started && !isLive && len >= 180_000 && t >= len - maxOf(20_000L, len / 20)) {
            ended = true
            save(finished = true)
        } else failover()
    }

    /** Skips to the next source not tried yet ("Next source" on the loading screen). */
    fun nextSource() = failover()

    /** A source failed: try the next untried one, like the official app and the Linux player. */
    private fun failover() {
        val next = ranked().firstOrNull { it.url !in tried }
        if (next != null) {
            App.toast("${current?.name ?: "Source"} failed, trying ${next.name}")
            play(next)
        } else if (fromCache && !refreshed) {
            // Every remembered source failed: they've probably expired. Ask the extension again, once.
            refreshed = true
            Engine.forget(api, playable.data)
            links.clear(); tried.clear(); current = null
            searching = true; error = null
            App.toast("Those sources expired; looking again…")
            scope.launch { findSources() }
        } else {
            error = "This source can't be played. Pick another one in Sources."
            buffering = false
        }
    }

    /** Records progress for Continue Watching and the episode list. */
    /** WeTrakr sync for this playback (movies and episodes; not live TV). */
    private val trakr by lazy { if (isLive) null else io.github.decodede.potatoflix.WeTrakr.Session(response, playable.season, playable.episode) }
    private fun percent(): Double = if (length > 0) player.currentPosition * 100.0 / length else 0.0

    /** True when the media itself is a live window (a cricket stream that never ends): no resume, no history. */
    fun isLiveTimeline(): Boolean = runCatching {
        val tl = player.currentTimeline
        if (tl.isEmpty) return@runCatching false
        val window = androidx.media3.common.Timeline.Window()
        tl.getWindow(player.currentMediaItemIndex.coerceIn(0, tl.windowCount - 1), window).isLive
    }.getOrDefault(false)

    private fun isLiveNow(): Boolean = isLive || isLiveTimeline()

    fun save(finished: Boolean = false) {
        if (finished) trakr?.stopped(100.0)
        if (isLive || !started || isLiveTimeline()) return
        val len = length.takeIf { it > 0 } ?: return
        val pos = if (finished) len else player.currentPosition
        Library.record(WatchEntry(
            api.name, response.url, response.name, response.posterUrl, playable.data,
            if (playables.size > 1) playable.label else null, pos / 1000.0, len / 1000.0, System.currentTimeMillis(),
        ))
    }

    fun release() {
        if (started) trakr?.stopped(percent())
        released = true
        io.github.decodede.potatoflix.CrashReport.playing(null)
        save()
        closeTorrent()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        loudness?.release()
        loudness = null
        player.removeListener(listener)
        player.release()
    }
}
