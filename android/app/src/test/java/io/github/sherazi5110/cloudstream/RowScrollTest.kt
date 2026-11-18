package io.github.sherazi5110.cloudstream

import io.github.sherazi5110.cloudstream.ui.RevealScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV rows: the selected poster stays about a card from the edges, so selecting the last visible one shows the next. */
class RowScrollTest {
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun `selecting near an edge reveals the next posters`() {
        val row = 1600f; val card = 200f
        // In the middle: no scrolling.
        assertEquals(0f, RevealScroll.calculateScrollDistance(600f, card, row))
        // The last fully visible poster (ends at the right edge): scrolls on by about a card.
        val d = RevealScroll.calculateScrollDistance(1400f, card, row)
        assertTrue("$d", d >= card)
        // Half off the left edge: scrolls back so the one before shows too.
        assertTrue(RevealScroll.calculateScrollDistance(-100f, card, row) < -100f)
    }
}
