package io.github.decodede.potatoflix

import io.github.decodede.potatoflix.ui.SourceFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sources panel's filters and languages. */
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

}
