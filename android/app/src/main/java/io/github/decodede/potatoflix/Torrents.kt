package io.github.decodede.potatoflix

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Torrent streaming (opt-in, Settings → Player): plays torrent sources (Stremio add-ons like Torrentio, magnet links
 * from extensions) while they download. libtorrent fetches the chosen video file in order, its first and last pieces
 * first, and a small web server on this device (127.0.0.1 only) hands the player each part as soon as it's there,
 * waiting for parts not downloaded yet (seeking moves the download there). The files are deleted when playback ends.
 *
 * While a torrent plays, this device also uploads to other people (that's how BitTorrent works) and its IP address is
 * visible to them: the setting says so before it can be turned on, and uploading is limited.
 */
object Torrents {
    private val prefs get() = App.context.getSharedPreferences("torrents", 0)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); if (!v) closeAll() }

    /** Upload limit in KB/s (0 = no limit). */
    var uploadLimitKb: Int
        get() = runCatching { prefs.getInt("upload_kb", 100) }.getOrDefault(100)
        set(v) { prefs.edit().putInt("upload_kb", v).apply(); session?.uploadRateLimit(v * 1024) }

    /** Tests: where downloads go and which address the session listens on. */
    @Volatile internal var dirOverride: File? = null
    @Volatile internal var listenOverride: String? = null
    private val dir get() = (dirOverride ?: File(App.context.cacheDir, "torrents")).apply { mkdirs() }

    data class Status(val phase: String, val peers: Int, val bytesPerSecond: Int, val ready: Float)

    fun isTorrent(link: ExtractorLink) =
        link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT || link.url.startsWith("magnet:")

    /** A Stremio-style torrent as a magnet link; [fileIdx] is kept as "so" (BEP 53: the file to fetch). */
    fun magnet(infoHash: String, name: String?, trackers: List<String>, fileIdx: Int?): String = buildString {
        append("magnet:?xt=urn:btih:").append(infoHash.lowercase())
        name?.takeIf { it.isNotBlank() }?.let { append("&dn=").append(URLEncoder.encode(it, "UTF-8")) }
        (trackers + DEFAULT_TRACKERS).distinct().forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        fileIdx?.takeIf { it >= 0 }?.let { append("&so=").append(it) }
    }

    /** Public trackers added to every magnet, so peers are found faster than through DHT alone. */
    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce", "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce", "udp://open.demonii.com:1337/announce",
    )

    private val videoExt = setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg")

    private class Open(val key: String, val handle: TorrentHandle, val info: TorrentInfo, val file: Int, val path: File, val size: Long) {
        @Volatile var closed = false
    }
    private val open = ConcurrentHashMap<String, Open>()

    @Volatile private var session: SessionManager? = null

    @Synchronized private fun session(): SessionManager = session ?: SessionManager().also { s ->
        val pack = SettingsPack().uploadRateLimit(uploadLimitKb * 1024).activeDownloads(4)
        listenOverride?.let { pack.listenInterfaces(it) }
        s.start(SessionParams(pack))
        session = s
    }

    /** The port this device's torrent session listens on (tests seed to it). */
    internal fun listenPort(): Int = session().swig().listen_port()

    /**
     * Starts [uri] (a magnet link, or the address of a .torrent file) and returns the local address the player
     * plays, once the start of the video is there. [onStatus] gets progress while it connects and buffers.
     */
    suspend fun open(uri: String, onStatus: (Status) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val s = session()
        onStatus(Status("Looking for people sharing it…", 0, 0, 0f))
        val info: TorrentInfo = if (uri.startsWith("magnet:")) {
            val bytes = s.fetchMagnet(uri, 90, dir) ?: throw IOException("No one is sharing this torrent right now (no peers found in 90 s)")
            TorrentInfo.bdecode(bytes)
        } else {
            val bytes = com.lagradost.cloudstream3.app.baseClient.newCall(okhttp3.Request.Builder().url(uri).build()).execute().use {
                if (!it.isSuccessful) throw IOException("The .torrent file didn't download (HTTP ${it.code})")
                it.body.bytes()
            }
            TorrentInfo.bdecode(bytes)
        }
        val key = info.infoHash().toHex()
        open[key]?.takeIf { !it.closed }?.let { return@withContext url(key) }
        val fs = info.files()
        val wanted = param(uri, "so")?.toIntOrNull()?.takeIf { it in 0 until fs.numFiles() }
            ?: (0 until fs.numFiles()).filter { fs.fileName(it).substringAfterLast('.').lowercase() in videoExt }.maxByOrNull { fs.fileSize(it) }
            ?: (0 until fs.numFiles()).maxBy { fs.fileSize(it) }
        val priorities = Array(fs.numFiles()) { if (it == wanted) Priority.TOP_PRIORITY else Priority.IGNORE }
        val peers = params(uri, "x.pe").mapNotNull { p -> p.substringBeforeLast(':').let { h -> p.substringAfterLast(':').toIntOrNull()?.let { TcpEndpoint(h, it) } } }
        s.download(info, dir, null, priorities, peers.ifEmpty { null }, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val handle = s.find(info.infoHash()) ?: throw IOException("The torrent couldn't be started")
        handle.prioritizeFiles(priorities)
        val o = Open(key, handle, info, wanted, File(dir, fs.filePath(wanted)), fs.fileSize(wanted))
        open[key] = o
        // The start (about 4 MB) and the end (where MP4 and MKV keep their index) before playing.
        val (first, last) = pieces(o, 0, o.size - 1)
        val head = (first..last).take(maxOf(2, (4 * 1024 * 1024) / info.pieceLength())).toList()
        val tail = listOf(last)
        (head + tail).forEachIndexed { i, p -> handle.piecePriority(p, Priority.TOP_PRIORITY); handle.setPieceDeadline(p, 500 + i * 200) }
        val need = (head + tail).distinct()
        var lastProgress = System.currentTimeMillis()
        var had = 0
        while (true) {
            if (o.closed) throw IOException("Stopped")
            val have = need.count { handle.havePiece(it) }
            val st = handle.status()
            onStatus(Status("Buffering", st.numPeers(), st.downloadRate(), have.toFloat() / need.size))
            if (have == need.size) break
            if (have > had) { had = have; lastProgress = System.currentTimeMillis() }
            if (System.currentTimeMillis() - lastProgress > 120_000)
                throw IOException(if (st.numPeers() == 0) "No one is sharing this torrent right now" else "This torrent is too slow to play (${st.numPeers()} peers)")
            delay(400)
        }
        url(key)
    }

    /** The status of a playing torrent, for the player's buffering line. */
    fun status(uri: String): Status? {
        val o = open.values.firstOrNull { uri.contains(it.key, ignoreCase = true) } ?: return null
        val st = o.handle.status()
        return Status("Playing", st.numPeers(), st.downloadRate(), st.progress())
    }

    /** Stops [uri]'s torrent and deletes what it downloaded. */
    fun close(uri: String) {
        val o = open.values.firstOrNull { uri.contains(it.key, ignoreCase = true) || uri.endsWith("/t/${it.key}") } ?: return
        o.closed = true
        open.remove(o.key)
        runCatching { session?.remove(o.handle, SessionHandle.DELETE_FILES) }
        if (open.isEmpty()) stopSession()
    }

    fun closeAll() { open.keys.toList().forEach { close(it) } }

    /** Tests: stops the torrent engine itself, so its native threads are gone before the test process exits. */
    @Synchronized internal fun shutdown() { closeAll(); runCatching { session?.stop() }; session = null }

    @Synchronized private fun stopSession() {
        val s = session ?: return
        session = null
        Thread { runCatching { s.stop() }; dir.listFiles()?.forEach { it.deleteRecursively() } }.start()
    }

    private fun param(uri: String, name: String) = params(uri, name).firstOrNull()
    private fun params(uri: String, name: String) = uri.substringAfter('?', "").split('&')
        .filter { it.substringBefore('=').equals(name, true) }.map { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }

    /** The first and last piece holding bytes [from]..[to] of the file. */
    private fun pieces(o: Open, from: Long, to: Long): Pair<Int, Int> =
        o.info.mapFile(o.file, from, 1).piece() to o.info.mapFile(o.file, to.coerceAtLeast(0), 1).piece()

    // ---------- the local web server the player reads from ----------

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "torrent-http").apply { isDaemon = true } }

    @Synchronized private fun url(key: String): String {
        val srv = server ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { s ->
            server = s
            Thread({ while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; pool.execute { runCatching { serve(c) }; runCatching { c.close() } } } }, "torrent-server")
                .apply { isDaemon = true }.start()
        }
        return "http://127.0.0.1:${srv.localPort}/t/$key"
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 60_000
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        var range: String? = null
        while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim() }
        val (method, path) = request.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val out = socket.getOutputStream()
        val o = open[path.substringAfter("/t/", "").substringBefore('?')]
        if (o == null || o.closed) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val size = o.size
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = (m?.groupValues?.get(2)?.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
        if (start >= size) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val type = when (o.path.extension.lowercase()) { "mp4", "m4v", "mov" -> "video/mp4"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; else -> "video/x-matroska" }
        val head = StringBuilder(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: $type\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n")
        if (m != null) head.append("Content-Range: bytes $start-$end/$size\r\n")
        out.write(head.append("\r\n").toString().toByteArray())
        if (method == "HEAD") return
        val buf = ByteArray(64 * 1024)
        var pos = start
        var raf: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val req = o.info.mapFile(o.file, pos, 1)
                waitFor(o, req.piece())
                // What this piece holds of the file from pos.
                val inPiece = (o.info.files().pieceSize(req.piece()) - req.start()).toLong()
                val n = minOf(inPiece, end - pos + 1, buf.size.toLong()).toInt()
                val f = raf ?: RandomAccessFile(o.path, "r").also { raf = it }
                f.seek(pos)
                f.readFully(buf, 0, n)
                out.write(buf, 0, n)
                pos += n
            }
            out.flush()
        } finally { runCatching { raf?.close() } }
    }

    /** Waits until [piece] is downloaded, asking for it (and the next few) first. */
    private fun waitFor(o: Open, piece: Int) {
        if (o.handle.havePiece(piece)) return
        val last = o.info.numPieces() - 1
        for (i in 0..8) { val p = piece + i; if (p <= last) o.handle.setPieceDeadline(p, 300 + i * 300) }
        val started = System.currentTimeMillis()
        while (!o.handle.havePiece(piece)) {
            if (o.closed) throw IOException("closed")
            if (System.currentTimeMillis() - started > 90_000) throw IOException("piece $piece didn't arrive")
            Thread.sleep(80)
        }
    }
}
