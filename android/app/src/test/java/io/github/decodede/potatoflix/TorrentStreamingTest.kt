package io.github.decodede.potatoflix

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.random.Random

/**
 * Torrent streaming end to end, on this computer only: a second libtorrent session shares a 6 MB "movie", the app's
 * engine joins it from a magnet link (the seeder's address in x.pe, as DHT would find it), buffers the start and
 * serves the file to the player over HTTP, including a jump into the middle (seeking).
 */
class TorrentStreamingTest {
    init {
        // On this computer libtorrent4j needs its Linux library (the Android ones are in the APK): unpack it.
        val so = javaClass.classLoader!!.getResources("lib/x86_64/libtorrent4j.so").toList().first { "libtorrent4j-linux" in it.toString() }
        val file = File.createTempFile("libtorrent4j", ".so").apply { deleteOnExit() }
        so.openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        System.setProperty("libtorrent4j.jni.path", file.absolutePath)
    }

    @Test(timeout = 180_000)
    fun `a magnet plays from its first bytes and seeks`(): Unit = runBlocking {
        val root = Files.createTempDirectory("torrent-test").toFile()
        val seedDir = File(root, "seed").apply { mkdirs() }
        val movie = File(seedDir, "Some Movie (2026)").apply { mkdirs() }
        val data = Random(7).nextBytes(6 * 1024 * 1024)
        File(movie, "Some.Movie.2026.1080p.mkv").writeBytes(data)
        File(movie, "sample.txt").writeText("not the video")

        val built = TorrentBuilder().path(movie).pieceSize(256 * 1024).flags(TorrentBuilder.V1_ONLY).generate()  // v1, like Torrentio's info hashes
        val info = TorrentInfo.bdecode(built.entry().bencode())
        val seeder = SessionManager().apply { start(SessionParams(SettingsPack().listenInterfaces("127.0.0.1:0"))) }
        try {
            seeder.download(info, seedDir, null, null, null, TorrentFlags.SEED_MODE)
            val seedPort = seeder.swig().listen_port()
            assertTrue(seedPort > 0)

            Torrents.dirOverride = File(root, "player")
            Torrents.listenOverride = "127.0.0.1:0"
            val magnet = Torrents.magnet(info.infoHash().toHex(), "Some Movie", emptyList(), null) + "&x.pe=127.0.0.1:$seedPort"
            val statuses = mutableListOf<Torrents.Status>()
            val url = Torrents.open(magnet) { statuses += it }
            assertTrue(url, url.startsWith("http://127.0.0.1:"))
            assertTrue("it reported buffering", statuses.any { it.phase == "Buffering" })

            fun get(range: String?): Pair<Int, ByteArray> {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                range?.let { c.setRequestProperty("Range", it) }
                return c.responseCode to c.inputStream.use { it.readBytes() }
            }
            // The whole file (the largest video was chosen, not sample.txt), as a player reading from the start.
            val (code, all) = get(null)
            assertEquals(200, code)
            assertArrayEquals(data, all)
            // A jump into the middle, as when seeking.
            val from = 3 * 1024 * 1024 + 12345
            val (code2, part) = get("bytes=$from-${from + 99_999}")
            assertEquals(206, code2)
            assertArrayEquals(data.copyOfRange(from, from + 100_000), part)

            Torrents.close(url)
            Thread.sleep(1500)
            assertTrue("the download is deleted", File(root, "player").walk().none { it.isFile && it.name.endsWith(".mkv") })
        } finally {
            Torrents.shutdown()
            runCatching { seeder.stop() }
            root.deleteRecursively()
        }
    }
}
