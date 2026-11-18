package io.github.sherazi5110.cloudstream

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.github.sherazi5110.cloudstream.ui.AppRoot
import io.github.sherazi5110.cloudstream.ui.CloudStreamTheme
import io.github.sherazi5110.cloudstream.ui.GlassBackdrop
import io.github.sherazi5110.cloudstream.ui.Nav
import io.github.sherazi5110.cloudstream.ui.Tab
import io.github.sherazi5110.cloudstream.ui.ThemeMode
import io.github.sherazi5110.cloudstream.ui.ThemeState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The theme without saving it (the test's settings store can't write). */
internal fun setMode(m: ThemeMode) {
    @Suppress("UNCHECKED_CAST")
    (ThemeState::class.java.getDeclaredField("mode\$delegate").apply { isAccessible = true }.get(ThemeState) as androidx.compose.runtime.MutableState<ThemeMode>).value = m
}

/** The liquid glass theme: panels must stay dark glass with readable text (they used to turn white). */
class GlassThemeTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10.copy(orientation = com.android.resources.ScreenOrientation.LANDSCAPE), maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        setMode(ThemeMode.GLASS)
        Nav.stack.clear()
    }

    @After
    fun tearDown() { setMode(ThemeMode.DARK) }

    @Test fun sourcesPanel() {
        val links = kotlinx.coroutines.runBlocking {
            listOf("HubDrive HubCloud [FSLv2] 1080p WEB-DL x264 2.1 GB", "Hblinks HubCloud [Pixeldrain] 1080p 10bit HEVC 1.4 GB",
                "HubDrive HubCloud [FSL Server] 720p WEB-DL 900 MB").mapIndexed { i, name ->
                com.lagradost.cloudstream3.utils.newExtractorLink("HDHub4U", name, "https://example.com/$i.mkv") { quality = if (i == 2) 720 else 1080 }
            }
        }
        paparazzi.snapshot("glass-sources-panel") {
            CloudStreamTheme {
                Box(Modifier.fillMaxSize()) {
                    GlassBackdrop(Modifier.fillMaxSize())
                    io.github.sherazi5110.cloudstream.ui.SourcesPanel(links, links[0].url, searching = true, onPick = {}, onClose = {})
                }
            }
        }
    }

    @Test fun styleSidePanel() = paparazzi.snapshot("glass-subtitle-style") {
        CloudStreamTheme { Box(Modifier.fillMaxSize()) { GlassBackdrop(Modifier.fillMaxSize()); io.github.sherazi5110.cloudstream.ui.SubtitleStylePanel(onApply = {}, onClose = {}) } }
    }
}

/** Liquid glass on a phone: the floating tab bar over the page. */
class GlassPhoneTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    @Before
    fun setUp() {
        App::class.java.getDeclaredField("context").apply { isAccessible = true }.set(App, paparazzi.context)
        ThemeState.load()
        setMode(ThemeMode.GLASS)
        Nav.stack.clear(); Nav.tab = Tab.MORE
    }

    @After
    fun tearDown() { setMode(ThemeMode.DARK); Nav.tab = Tab.HOME }

    @Test fun settings() = paparazzi.snapshot("glass-phone-settings") { CloudStreamTheme { AppRoot() } }
}
