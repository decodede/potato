package io.github.decodede.potatoflix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the Android app sends WeTrakr for a playback (the sign-in and scrobble calls are tested on the desktop app). */
class WeTrakrTest {
    @Test fun episodeAndMovieBodies() {
        val e = WeTrakr.media("Breaking Bad", 2008, false, 1, 2, null, null, 12.54)
        assertEquals("Breaking Bad", e.getJSONObject("show").getString("title"))
        assertEquals(2008, e.getJSONObject("show").getInt("year"))
        assertEquals(1, e.getJSONObject("episode").getInt("season"))
        assertEquals(2, e.getJSONObject("episode").getInt("number"))
        assertEquals(12.5, e.getDouble("progress"), 0.001)
        assertFalse(e.has("movie"))

        val m = WeTrakr.media("Dune: Part Two", 2024, true, null, null, 693134, "tt15239678", 50.0)
        assertEquals(693134, m.getJSONObject("movie").getJSONObject("ids").getInt("tmdb"))
        assertEquals("tt15239678", m.getJSONObject("movie").getJSONObject("ids").getString("imdb"))
        assertFalse(m.has("show"))
    }
}
