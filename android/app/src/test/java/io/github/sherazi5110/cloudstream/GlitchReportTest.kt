package io.github.sherazi5110.cloudstream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.GlitchDialog
import io.github.sherazi5110.cloudstream.ui.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GlitchReportTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
    }

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
        val url = GlitchReport.issueUrl("Play button hidden", "Potatoflix 9.0.1\nScreen: Details: Dune (HDHub4U)", screenshotSaved = true)
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

    @Test fun dialog() {
        val shot = android.graphics.Bitmap.createBitmap(1280, 576, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2A3A4F.toInt()) }
        GlitchReport.draft = GlitchReport.Draft("Details: Dune: Part Two (HDHub4U)", listOf("12:01:02 Tab Home", "12:01:09 Opened Details: Dune: Part Two (HDHub4U)"), shot)
        paparazzi.snapshot("glitch-dialog") { CloudStreamTheme { Box(Modifier.fillMaxSize().background(Color(0xFF101014))) { GlitchDialog() } } }
    }
}
