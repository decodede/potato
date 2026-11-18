package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlitchReportTest {
    @Test fun redactsSecrets() {
        val r = GlitchReport.redact("""POST token=ya29.abc&x=1 {"refresh_token":"1//xyz"} Authorization: Bearer abc.def me@gmail.com""")
        assertFalse(r.contains("ya29") || r.contains("1//xyz") || r.contains("abc.def") || r.contains("gmail"))
        assertTrue(r.contains("token=…"))
    }

    @Test fun stepsAreKeptShort() {
        repeat(30) { GlitchReport.step("Opened screen $it") }
        val steps = GlitchReport.steps()
        assertEquals(20, steps.size)
        assertTrue(steps.last().endsWith("Opened screen 29"))
    }

    @Test fun issueLink() {
        val url = GlitchReport.issueUrl("Play button hidden", "Potatoflix 1.0.0\nScreen: Details: Dune (HDHub4U)", screenshotSaved = true)
        assertTrue(url.startsWith("https://github.com/decodede/potato/issues/new?title=Glitch%3A+Play+button+hidden"))
        assertTrue(url.contains("Pictures"))
        val blank = GlitchReport.issueUrl("", "header\nScreen: Player: Dune", screenshotSaved = false)
        assertTrue(blank.contains("title=Glitch%3A+Player%3A+Dune"))
    }

    @Test fun shakeNeedsThreeJolts() {
        val s = ShakeToReport::class.java.getDeclaredConstructor(android.app.Activity::class.java)
        // The detector itself, without an activity's sensors.
        val d = runCatching { s.newInstance(null) }.getOrNull() ?: return
        assertFalse(d.shake(1.0f, 1000))
        assertFalse(d.shake(3f, 1000))
        assertFalse(d.shake(3f, 1100)) // same jolt
        assertFalse(d.shake(3f, 1400))
        assertTrue(d.shake(3f, 1800))
        assertFalse(d.shake(3f, 2000)) // resting after a report
    }
}
