package io.github.decodede.potatoflix

import io.github.decodede.potatoflix.player.HlsVariants
import io.github.decodede.potatoflix.ui.SourceFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sources panel's filters, and the qualities inside one HLS source (as official CloudStream offers them). */
class SourcesAndQualityTest {
    @Test
    fun `languages, audio and quality come from source names`() {
        val name = "🐧 PenguPlay 🧊 1080p • 4KHDHub · R2 · 🍿 A Dog's Perfect Christmas (2026) [Hindi-English] Dual Audio"
        assertEquals(listOf("Hindi", "English", "Dual audio", "1080p"), SourceFilters.tags(name, 0))
        assertEquals(listOf("Tamil", "4K"), SourceFilters.tags("VegaMovies Tam 2160p", 0))
        // The extension's quality number wins over the name.
        assertEquals(listOf("720p"), SourceFilters.tags("Some server", 720))
        // Not a language: "mal" or "tel" inside other words.
        assertEquals(emptyList<String>(), SourceFilters.tags("Hotel Normal stream", 0))
        assertTrue(SourceFilters.matches("PenguPlay 1080p 4KHDHub Hindi", "hindi 1080"))
        assertFalse(SourceFilters.matches("PenguPlay 1080p 4KHDHub English", "hindi"))
        assertEquals(listOf("Hindi", "English", "1080p", "720p"), SourceFilters.chips(listOf(listOf("Hindi", "720p"), listOf("Hindi", "English", "1080p"))))
    }

    @Test
    fun `language codes become the names sources use`() {
        assertEquals("Hindi", Engine.languageName("hi"))
        assertEquals("Japanese", Engine.languageName("ja"))
        assertEquals("Hindi", Engine.languageName("hindi"))
    }

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:4
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
        480/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=2560x1440,CODECS="avc1.640032,mp4a.40.2"
        https://cdn.example/1440/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
        1080b/index.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="iframes.m3u8"
    """.trimIndent()

    @Test
    fun `the qualities of an HLS master, best first`() {
        val m = HlsVariants.parse(master, "https://media.example/movie/master.m3u8")!!
        assertEquals(listOf("1440p", "1080p", "480p"), m.variants.map { it.label })
        assertEquals("https://media.example/movie/1080/index.m3u8", m.variants[1].url)  // the better of the two 1080p
        assertEquals("https://cdn.example/1440/index.m3u8", m.variants[0].url)
        assertFalse(m.separateAudio)
        // A media playlist (one quality) has nothing to choose.
        assertNull(HlsVariants.parse("#EXTM3U\n#EXTINF:6,\nseg1.ts\n", "https://x.example/a.m3u8"))
    }

    @Test
    fun `with separate audio, the qualities are still listed`() {
        val withAudio = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Hindi",LANGUAGE="hi",URI="audio/hi.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,AUDIO="aud"
            480.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,AUDIO="aud"
            1080.m3u8
        """.trimIndent()
        val m = HlsVariants.parse(withAudio, "https://media.example/m/master.m3u8")!!
        assertTrue(m.separateAudio)
        assertEquals(listOf("1080p", "480p"), m.variants.map { it.label })
    }
}
