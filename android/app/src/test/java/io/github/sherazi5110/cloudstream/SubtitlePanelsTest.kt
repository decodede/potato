package io.github.sherazi5110.cloudstream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.SubtitleStylePanel
import io.github.sherazi5110.cloudstream.ui.SyncPanel
import io.github.sherazi5110.cloudstream.ui.ThemeState
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The player's Subtitle style and Sync panels, over a dark "video", on a landscape phone. */
class SubtitlePanelsTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
    }

    @Test fun style() = paparazzi.snapshot("player-subtitle-style") {
        CloudStreamTheme { Box(Modifier.fillMaxSize().background(Color(0xFF203040))) { SubtitleStylePanel(onApply = {}, onClose = {}) } }
    }

    @Test fun sync() = paparazzi.snapshot("player-subtitle-sync") {
        CloudStreamTheme { Box(Modifier.fillMaxSize().background(Color(0xFF203040))) { SyncPanel(-1300L, set = { _ -> }, onClose = {}) } }
    }
}
