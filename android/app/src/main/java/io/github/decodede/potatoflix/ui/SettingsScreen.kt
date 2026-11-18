package io.github.decodede.potatoflix.ui

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.decodede.potatoflix.App
import io.github.decodede.potatoflix.DnsProvider
import io.github.decodede.potatoflix.Metadata
import io.github.decodede.potatoflix.Network
import io.github.decodede.potatoflix.PluginManager
import io.github.decodede.potatoflix.Profiles
import io.github.decodede.potatoflix.Settings
import kotlinx.coroutines.launch

enum class Section(val title: String, val text: String, val icon: ImageVector) {
    EXTENSIONS("Extensions", "Repositories, Stremio add-ons", Icons.Filled.Extension),
    PROVIDERS("Providers", "Languages, media types, extension updates, test all", Icons.Filled.Language),
    GENERAL("General", "Downloads, site addresses, GitHub proxy", Icons.Filled.Tune),
    BACKUP("Updates and backup", "App updates, back up and restore", Icons.Filled.Backup),
    PROFILES("Profiles", "Who's watching, kids, adult content", Icons.Filled.People),
    APPEARANCE("Appearance", "Theme, accent colour, layout", Icons.Filled.Palette),
    PLAYER("Player", "Sources, quality, subtitles, audio", Icons.Filled.PlayCircle),
    WETRAKR("WeTrakr sync", "Mark what you watch on WeTrakr", Icons.Filled.Sync),
    METADATA("Metadata & integrations", "TMDB, IMDb ratings, AniList", Icons.Filled.Star),
    NETWORK("Network", "DNS over HTTPS, cookies", Icons.Filled.Public),
    ABOUT("About", "Version, source code", Icons.Filled.Info),
    LOGS("Logs", "Recent app events: loading, playing, errors", Icons.Filled.BugReport),
}

/** Settings (the "More" tab on phones): sections in a list; on wide screens the section shows beside it. */
@Composable
fun SettingsScreen() {
    var selected by remember { mutableStateOf(Section.PROFILES) }
    val wide = isWide
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isBlank()) Section.entries.toList()
        else Section.entries.filter { it.title.lowercase().contains(q) || it.text.lowercase().contains(q) || SECTION_KEYWORDS[it].orEmpty().any { k -> k.contains(q) } }
    Row(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.then(if (wide) Modifier.width(300.dp) else Modifier.fillMaxWidth()).fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalBottomInset.current), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (!wide) ProfileButton(32.dp)
            }
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Filled.Search, null) })
            if (shown.isEmpty()) Text("No matching settings", color = Palette.muted, modifier = Modifier.padding(vertical = 16.dp))
            shown.forEach { s ->
                val on = wide && s == selected
                Row(
                    Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f) {
                        when {
                            s == Section.EXTENSIONS -> Nav.select(Tab.EXTENSIONS)
                            wide -> selected = s
                            else -> Nav.open(Screen.SettingsPage(s.name))
                        }
                    }.background(if (on) Palette.accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, tint = if (on) Palette.accent else Palette.muted)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        if (!wide) Text(s.text, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!wide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.muted)
                }
            }
        }
        if (wide) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 760.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
            Text(selected.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            SectionContent(selected)
        }
    }
}

/** Portrait: one section on its own page. */
@Composable
fun SettingsPageScreen(screen: Screen.SettingsPage) {
    val section = Section.valueOf(screen.section)
    Column(Modifier.fillMaxSize()) {
        BackBar(section.title)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) { SectionContent(section) }
    }
}

/** Search keywords per section, for the box above the list (lowercase substrings). */
private val SECTION_KEYWORDS: Map<Section, List<String>> = mapOf(
    Section.EXTENSIONS to listOf("repository", "repo", "plugin", "extension", "add-on", "stremio", "install", "update"),
    Section.PROVIDERS to listOf("language", "media type", "auto update", "test extension", "provider", "source show"),
    Section.GENERAL to listOf("download", "connection", "background", "site", "clone", "proxy", "cache", "storage", "clear"),
    Section.BACKUP to listOf("backup", "restore", "drive", "update", "version", "sync"),
    Section.PROFILES to listOf("profile", "kids", "pin", "nsfw", "adult"),
    Section.APPEARANCE to listOf("theme", "dark", "amoled", "midnight", "glass", "accent", "colour", "color", "banner", "spoiler", "presets", "layout"),
    Section.PLAYER to listOf("playback", "quality", "codec", "decoder", "buffer", "4k", "subtitle", "audio", "language", "rotate", "vertical", "zoom", "next episode", "skip", "torrent", "volume", "brightness", "hdr", "speed", "sync", "auto"),
    Section.WETRAKR to listOf("wetrakr", "scrobbl", "track", "watching"),
    Section.METADATA to listOf("tmdb", "imdb", "rating", "anilist", "metadata", "trakt"),
    Section.NETWORK to listOf("dns", "doh", "cookie", "network", "proxy", "certificate"),
    Section.ABOUT to listOf("version", "about", "report", "github", "source", "license", "glitch"),
    Section.LOGS to listOf("log", "logs", "crash", "error", "debug"),
)

@Composable
private fun SectionContent(s: Section) {
    when (s) {
        Section.EXTENSIONS -> Unit
        Section.PROFILES -> ProfilesSection()
        Section.APPEARANCE -> AppearanceSection()
        Section.PLAYER -> PlayerSection()
        Section.WETRAKR -> WeTrakrSection()
        Section.METADATA -> MetadataSection()
        Section.NETWORK -> NetworkSection()
        Section.ABOUT -> AboutSection()
        Section.LOGS -> LogsSection()
        Section.PROVIDERS -> ProvidersSection()
        Section.GENERAL -> GeneralSection()
        Section.BACKUP -> BackupSection()
    }
}

@Composable
internal fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.accent,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))

// ---------------------------------------------------------------- sections

@Composable
private fun ProfilesSection() {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = Profiles.onChange { version++ }; onDispose { off() } }
    val all = remember(version) { Profiles.all() }
    all.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).card(RoundedCornerShape(10.dp), focusScale = 1.02f) { ProfileUi.editing = p }
            .background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.avatar, 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.name + if (p.id == ProfileUi.current.id) "  (you)" else "", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull("Kids".takeIf { p.kids }, "PIN".takeIf { p.pin != null }).joinToString(" · ").ifBlank { "Grown-up" }, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Filled.Edit, "Edit", tint = Palette.muted)
        }
    }
    PillButton("Add profile", Icons.Filled.Add, Modifier.padding(top = 8.dp), compact = true) { ProfileUi.creating = true }
    var ask by remember { mutableStateOf(Profiles.askOnStart) }
    Heading("Start")
    SwitchRow("Ask \"Who's watching?\" at start", "Only with more than one profile.", ask) { ask = it; Profiles.askOnStart = it }
    if (!ProfileUi.current.kids) {
        Heading("Adult content")
        var nsfw by remember(version) { mutableStateOf(Settings.showNsfw) }
        var confirm by remember { mutableStateOf(false) }
        SwitchRow("Show adult (NSFW) content", "For ${ProfileUi.current.name} only. Like the official app, adult (NSFW) extensions are hidden until this is on.", nsfw) { on ->
            if (!on) { Settings.showNsfw = false; nsfw = false; PluginManager.bumpVersion() } else confirm = true
        }
        if (confirm) SimpleDialog("Show adult content?", { confirm = false }, confirm = "Turn on", onConfirm = {
            confirm = false
            val enable = { Settings.showNsfw = true; nsfw = true; PluginManager.bumpVersion() }
            if (Profiles.hasParentPin) ProfileUi.pin = ProfileUi.PinRequest("Parent PIN", "Enter the parent PIN to allow adult content.", Profiles::checkParentPin) { enable() }
            else enable()
        }) { Text("Adult extensions and titles will show on Home, in Search and in Extensions for this profile.", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun AppearanceSection() {
    Heading("Presets")
    ChoiceChips(ThemePreset.entries, { it.label }, { ThemeState.preset == it }) { ThemeState.apply(it) }
    Heading("Theme")
    ChoiceChips(ThemeMode.entries, { it.label }, { ThemeState.mode == it }) { ThemeState.changeMode(it) }
    Heading("Accent colour")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Accent.entries.forEach { a ->
            val on = ThemeState.accentHex.equals(a.hex, true)
            Box(Modifier.size(40.dp).card(CircleShape) { ThemeState.changeAccent(a.hex) }.background(colorOf(a.hex), CircleShape)
                .then(if (on) Modifier.border(3.dp, Color.White, CircleShape) else Modifier))
        }
    }
    Hint("Each profile has its own look.")
    Heading("Layout")
    var layout by remember { mutableStateOf(Settings.layout) }
    val auto = if (App.detectTv()) "TV" else "Phone"
    ChoiceChips(listOf("auto", "phone", "tv"), { when (it) { "tv" -> "TV (remote)"; "phone" -> "Phone (touch)"; else -> "Automatic ($auto)" } }, { it == layout }) {
        layout = it; Settings.layout = it
    }
    Hint("Phone/TV changes how the app reacts to touch and the remote; it applies when the app restarts. Landscape screens always get the side rail.")
    Heading("Home")
    var slider by remember { mutableStateOf(Settings.heroSlider) }
    SwitchRow("Banner slides through the top titles", null, slider) { slider = it; Settings.heroSlider = it }
    var spoiler by remember { mutableStateOf(Settings.antiSpoiler) }
    SwitchRow("Anti-spoiler", "Hide pictures and descriptions of episodes you haven't watched.", spoiler) { spoiler = it; Settings.antiSpoiler = it }
}

@Composable
private fun PlayerSection() {
    Heading("Playback")
    var autoPlay by remember { mutableStateOf(Settings.autoPlay) }
    ChoiceChips(listOf(true, false), { if (it) "Best source, automatically" else "Let me choose" }, { it == autoPlay }) {
        autoPlay = it; Settings.autoPlay = it
    }
    Hint(if (autoPlay) "Starts as soon as a decent source is found." else "The sources list opens when you press Play.")
    var next by remember { mutableStateOf(Settings.autoPlayNext) }
    SwitchRow("Next episode automatically", "From the credits, with a countdown; Back cancels.", next, icon = Icons.Filled.SkipNext) { next = it; Settings.autoPlayNext = it }
    var skipBtns by remember { mutableStateOf(Settings.skipButtons) }
    SwitchRow("Skip intro buttons (anime)", "Intro, recap and credits times from AniSkip.", skipBtns, icon = Icons.Filled.ClosedCaption) { skipBtns = it; Settings.skipButtons = it }
    var autoSkip by remember { mutableStateOf(Settings.autoSkip) }
    SwitchRow("Skip intros automatically (anime)", null, autoSkip, icon = Icons.Filled.SkipNext) { autoSkip = it; Settings.autoSkip = it }

    Heading("Quality")
    var quality by remember { mutableStateOf(Settings.preferredQuality) }
    ChoiceChips(listOf(0, 2160, 1080, 720, 480), { if (it == 0) "Best" else "${it}p" }, { it == quality }) { quality = it; Settings.preferredQuality = it }
    var prepare by remember { mutableStateOf(Settings.prepareSources) }
    SwitchRow("Find sources before Play", "Looks them up in the background when you open a title's page; kept for 20 minutes.", prepare, icon = Icons.Filled.Download) { prepare = it; Settings.prepareSources = it }

    Heading("Picture")
    if (!isTv) {
        var rotation by remember { mutableStateOf(Settings.playerOrientation) }
        Text("Rotation", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        ChoiceChips(listOf("LANDSCAPE", "PORTRAIT", "AUTO"), { mapOf("LANDSCAPE" to "Landscape", "PORTRAIT" to "Portrait", "AUTO" to "As the device is held")[it]!! }, { it == rotation }) {
            rotation = it; Settings.playerOrientation = it
        }
    }
    var autoRotate by remember { mutableStateOf(Settings.autoRotatePortrait) }
    SwitchRow("Auto-rotate vertical videos", "Vertical videos play in portrait and back, without rotating anything.", autoRotate, icon = Icons.Filled.ScreenRotation) {
        autoRotate = it; Settings.autoRotatePortrait = it
    }
    var zoomMode by remember { mutableStateOf(Settings.playerZoom) }
    Text("Picture size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    ChoiceChips(io.github.decodede.potatoflix.player.Zoom.entries.map { it.name }, { n -> io.github.decodede.potatoflix.player.Zoom.valueOf(n).label }, { it == zoomMode }) {
        zoomMode = it; Settings.playerZoom = it
    }

    Heading("Codec & buffer")
    var decoding by remember { mutableStateOf(Settings.softwareDecoding) }
    ChoiceChips(listOf(-1, 0, 2, 1), { mapOf(-1 to "Automatic", 0 to "HW + SW", 2 to "SW + HW", 1 to "HW only")[it]!! }, { it == decoding }) {
        decoding = it; Settings.softwareDecoding = it
    }
    Hint("Automatic: FFmpeg fills in for codecs this hardware can't do. Applies on the next playback.")
    var buffer by remember { mutableStateOf(Settings.bufferProfile) }
    ChoiceChips(listOf(-1, 0, 1, 2), { mapOf(-1 to "Automatic", 0 to "Low RAM", 1 to "Balanced", 2 to "4K / high bitrate")[it]!! }, { it == buffer }) {
        buffer = it; Settings.bufferProfile = it
    }
    Hint("Automatic sizes the buffer to this device's memory. Applies on the next playback.")

    Heading("Audio")
    var audio by remember { mutableStateOf(Settings.audioLanguage) }
    LanguageChips(audio) { audio = it; Settings.audioLanguage = it }
    OutlinedTextField(audio, { audio = it; Settings.audioLanguage = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Or type codes, in order, e.g. hi,en") })
    Hint("Sources whose names say this language come first; in a source with several audio tracks, this one is picked.")
    var level by remember { mutableStateOf(Settings.levelAudio) }
    SwitchRow("Even out loud and quiet sounds", "Voices clearer, background quieter. Also in the player's Audio menu.", level, icon = Icons.AutoMirrored.Filled.VolumeUp) {
        level = it; Settings.levelAudio = it
    }

    Heading("Subtitles")
    var subs by remember { mutableStateOf(Settings.subtitleLanguage) }
    LanguageChips(subs) { subs = it; Settings.subtitleLanguage = it }
    OutlinedTextField(subs, { subs = it; Settings.subtitleLanguage = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Or type codes, in order, e.g. en or ar,en") })
    Hint("Turned on themselves when the source has them. Style, size and online subtitles: the player's Subtitles menu.")

    Heading("Limits")
    var timeout by remember { mutableStateOf(Settings.sourcesTimeoutSeconds) }
    ChoiceChips(listOf(30, 60, 90, 120), { "$it s" }, { it == timeout }) { timeout = it; Settings.sourcesTimeoutSeconds = it }
    Hint("How long to wait for a source before giving up on it.")
    TorrentSettings()
}
@Composable
private fun MetadataSection() {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(Metadata.tmdbKey) }
    var status by remember { mutableStateOf<String?>(null) }
    Hint("Add your free TMDB key (themoviedb.org → Settings → API) for ratings on posters, wide backdrops and title logos, cast with photos, trailers and similar titles. IMDb ratings (Cinemeta) and AniList scores need no key.")
    OutlinedTextField(key, { key = it.trim(); Metadata.tmdbKey = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("TMDB API key (v3) or read access token (v4)") })
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton("Test key", null, primary = false, compact = true) {
            status = "Testing…"
            scope.launch { status = Metadata.testKey(key) ?: "The key works." }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp)) }
    }
    Heading("Use")
    var tmdb by remember { mutableStateOf(Metadata.tmdbEnabled) }
    SwitchRow("TMDB", null, tmdb) { tmdb = it; Metadata.tmdbEnabled = it }
    var posters by remember { mutableStateOf(Metadata.posterRatings) }
    SwitchRow("Ratings on posters", null, posters) { posters = it; Metadata.posterRatings = it }
    var art by remember { mutableStateOf(Metadata.artwork) }
    SwitchRow("Backdrops and title logos", null, art) { art = it; Metadata.artwork = it }
    var extras by remember { mutableStateOf(Metadata.extras) }
    SwitchRow("Cast, trailers and similar titles", null, extras) { extras = it; Metadata.extras = it }
    var cinemeta by remember { mutableStateOf(Metadata.cinemeta) }
    SwitchRow("IMDb ratings (Cinemeta)", null, cinemeta) { cinemeta = it; Metadata.cinemeta = it }
    var anilist by remember { mutableStateOf(Metadata.anilist) }
    SwitchRow("AniList scores for anime", null, anilist) { anilist = it; Metadata.anilist = it }
    var lang by remember { mutableStateOf(Metadata.language) }
    OutlinedTextField(lang, { lang = it.trim(); Metadata.language = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text("Language, e.g. en-US") })
    Heading("Cache")
    var stats by remember { mutableStateOf(Metadata.cacheStats()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${stats.first} entries · ${stats.second / 1024} KB", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillButton("Clear", Icons.Filled.Delete, primary = false, compact = true) { Metadata.clearCache(); stats = Metadata.cacheStats() }
    }
}

@Composable
private fun NetworkSection() {
    Heading("DNS over HTTPS")
    var dns by remember { mutableStateOf(Settings.dns) }
    ChoiceChips(DnsProvider.entries, { it.label }, { it == dns }) { dns = it; Settings.dns = it }
    var fallback by remember { mutableStateOf(Settings.dnsFallback) }
    SwitchRow("Fall back to system DNS", "When the resolver is slow or blocked.", fallback) { fallback = it; Settings.dnsFallback = it }
    Hint("Applies when the app restarts. Helps when your network blocks some sites.")
    Heading("Cookies")
    var cookies by remember { mutableStateOf(Network.cookies.size) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$cookies cookies stored", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillButton("Clear", Icons.Filled.Delete, primary = false, compact = true) { Network.cookies.clear(); cookies = 0 }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: io.github.decodede.potatoflix.BuildConfig.VERSION_NAME }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Logo(48.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text("Potatoflix", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${versionName ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Text("Based on Stream Goat (Syed Ali Hamza)", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
    }
    PillButton("Source code on GitHub", null, primary = false, compact = true) {
        com.lagradost.cloudstream3.CloudStreamApp.openBrowser("https://github.com/decodede/potato", context.findActivity())
    }
    // A problem without a crash: GitHub's new-issue page with the device and version filled in (a QR code on TVs).
    PillButton("View the logs", Icons.Filled.BugReport, primary = false, compact = true, modifier = Modifier.padding(top = 8.dp)) {
        Nav.open(Screen.SettingsPage("LOGS"))
    }
    PillButton("Report a glitch or problem", null, primary = false, compact = true, modifier = Modifier.padding(top = 8.dp)) {
        // From Settings there's no screen to picture: the last steps say where it happened.
        io.github.decodede.potatoflix.GlitchReport.draft = io.github.decodede.potatoflix.GlitchReport.Draft(
            "Settings (reported from Settings → About)", io.github.decodede.potatoflix.GlitchReport.steps(), null)
    }
    Hint(if (App.isTv) "Tip: to include a picture of a screen, choose Report a glitch in the profile menu (top right) or the player's top bar."
        else "Tip: to include a picture of a screen, shake the phone there, choose Report a glitch in the profile menu, or tap the bug button in the player.")
    if (!App.isTv) {
        var shake by remember { mutableStateOf(io.github.decodede.potatoflix.Settings.shakeToReport) }
        SwitchRow("Shake to report a glitch", "A firm shake opens Report a glitch with a picture of the screen", shake) {
            shake = it; io.github.decodede.potatoflix.Settings.shakeToReport = it
        }
    }
    Hint("GPL-3.0. Extensions and their content come from third parties; this app hosts nothing.")
}

/** Settings → Player → Torrents: off until the person has read what it means. */
@Composable
private fun TorrentSettings() {
    Heading("Torrents")
    var on by remember { mutableStateOf(io.github.decodede.potatoflix.Torrents.enabled) }
    var asking by remember { mutableStateOf(false) }
    SwitchRow("Torrent streaming", "Plays torrent sources (Torrentio and other add-ons, magnet links) while they download. Off by default.", on) {
        if (it) asking = true else { on = false; io.github.decodede.potatoflix.Torrents.enabled = false }
    }
    if (on) {
        var limit by remember { mutableStateOf(io.github.decodede.potatoflix.Torrents.uploadLimitKb) }
        Text("Upload limit while watching", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        ChoiceChips(listOf(50, 100, 500, 0), { if (it == 0) "No limit" else if (it >= 1000) "${it / 1000} MB/s" else "$it KB/s" }, { it == limit }) {
            limit = it; io.github.decodede.potatoflix.Torrents.uploadLimitKb = it
        }
    }
    Hint("Faster and private: set Torrentio up with a debrid service (Real-Debrid, AllDebrid, Premiumize, TorBox…) at " +
        "torrentio.strem.fun/configure and install that link instead. Its streams are then ordinary links, no torrenting on this device.")
    if (asking) SimpleDialog("Turn on torrent streaming?", { asking = false }, confirm = "I understand, turn it on", onConfirm = {
        on = true; io.github.decodede.potatoflix.Torrents.enabled = true; asking = false
    }) {
        Hint("While a torrent plays, this device downloads it from other people and also uploads parts of it to them; that's " +
            "how BitTorrent works. Your IP address is visible to everyone sharing the same torrent, and in many countries " +
            "copyright holders watch torrents for that. Many people use a VPN.")
        Hint("Only stream what you're allowed to. Uploading is limited (Upload limit, below), and the downloaded file is " +
            "deleted when you stop watching. A debrid service avoids all this (see below the switch).")
    }
}

/** Common languages as chips that add or remove their code in a "hi,en" setting (order kept). */
@Composable
private fun LanguageChips(value: String, onChange: (String) -> Unit) {
    val codes = listOf("en", "hi", "ur", "ta", "te", "ml", "bn", "pa", "ja", "ko", "zh", "es", "fr", "de", "ar", "tr", "pt", "ru", "id")
    val chosen = value.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
        items(codes.size) { i ->
            val code = codes[i]
            val on = code in chosen || io.github.decodede.potatoflix.Engine.languageName(code).lowercase() in chosen
            Text(io.github.decodede.potatoflix.Engine.languageName(code), style = MaterialTheme.typography.labelLarge, modifier = Modifier
                .card(RoundedCornerShape(50)) { onChange((if (on) chosen.filterNot { it == code } else chosen + code).joinToString(",")) }
                .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}


/**
 * Sync with WeTrakr: a code to enter on wetrakr.com/activate (a QR code on TVs), then what's watched here is
 * marked watched there. Each profile connects its own account.
 */
@Composable
private fun WeTrakrSection() {
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(io.github.decodede.potatoflix.WeTrakr.connected) }
    var code by remember { mutableStateOf<io.github.decodede.potatoflix.WeTrakr.DeviceCode?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    Hint("WeTrakr (wetrakr.com) is a free tracker for movies, shows and anime. Connected, what you watch here shows as now watching " +
        "there and is marked watched once you've seen 80% of it. Each profile connects its own account; only a sign-in token is kept on this device.")
    when {
        !io.github.decodede.potatoflix.WeTrakr.available -> Hint("Not available in this build yet.")
        connected -> {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Connected to WeTrakr", style = MaterialTheme.typography.titleSmall, color = Palette.accent, modifier = Modifier.weight(1f))
                PillButton("Disconnect", null, primary = false, compact = true) {
                    io.github.decodede.potatoflix.WeTrakr.disconnect(); connected = false; status = null
                }
            }
            var share by remember { mutableStateOf(io.github.decodede.potatoflix.WeTrakr.shareNowPlaying) }
            SwitchRow("Show what I'm watching", "WeTrakr shows the title as playing, with its progress. Off: titles are only marked watched when you finish them.", share) {
                share = it; io.github.decodede.potatoflix.WeTrakr.shareNowPlaying = it
            }
        }
        code != null -> {
            val c = code!!
            Text("Go to ${c.url.removePrefix("https://")} on your phone or computer and enter:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(c.userCode, style = MaterialTheme.typography.displaySmall, color = Palette.accent, modifier = Modifier.padding(vertical = 6.dp))
            if (App.isTv) {
                val qr = remember(c.url) { qrBitmap(c.url + "?code=" + c.userCode, 480).asImageBitmap() }
                androidx.compose.foundation.Image(qr, "QR code for WeTrakr", Modifier.size(180.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(Modifier.size(22.dp))
                Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp).weight(1f))
                PillButton("Cancel", null, primary = false, compact = true) { job?.cancel(); code = null }
            }
        }
        else -> PillButton("Connect WeTrakr", Icons.Filled.Link, compact = true, modifier = Modifier.padding(top = 6.dp)) {
            status = null
            job = scope.launch {
                try {
                    val c = io.github.decodede.potatoflix.WeTrakr.start()
                    code = c
                    val until = System.currentTimeMillis() + c.expiresIn * 1000L
                    while (System.currentTimeMillis() < until) {
                        kotlinx.coroutines.delay(c.interval * 1000L)
                        if (io.github.decodede.potatoflix.WeTrakr.poll(c)) { connected = true; code = null; App.toast("WeTrakr connected"); return@launch }
                    }
                    code = null; status = "The code expired. Try again."
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { code = null; status = "Couldn't connect: ${e.message}" }
            }
        }
    }
    status?.let { Hint(it) }
}

/** In-memory event log, newest first: failures while loading extensions, network errors, playback problems. */
@Composable
private fun LogsSection() {
    val context = LocalContext.current
    val logVersion = io.github.decodede.potatoflix.AppLog.version
    val lines = remember(logVersion) { io.github.decodede.potatoflix.AppLog.all().asReversed() }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        PillButton("Copy all", Icons.Filled.Edit, compact = true) {
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Potatoflix logs", lines.joinToString("\n")))
            io.github.decodede.potatoflix.App.toast("Logs copied")
        }
        PillButton("Clear", null, primary = false, compact = true) { io.github.decodede.potatoflix.AppLog.clear() }
    }
    Hint("Newest first. Kept in memory only; cleared when the app closes.")
    if (lines.isEmpty()) {
        Hint("Nothing logged yet.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (line.length > 2 && line[9] == 'E') Palette.accent else Palette.muted,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}
