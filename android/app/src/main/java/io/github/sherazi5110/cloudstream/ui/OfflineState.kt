package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * No internet: a short message, a retry and the way to the downloads, which still play.
 */
@Composable
fun OfflineState(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "offline").animateFloat(0.55f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
        androidx.compose.foundation.layout.Box(Modifier.size(120.dp).graphicsLayer { alpha = pulse }, contentAlignment = Alignment.Center) { Logo(120.dp) }
        Text("You're offline", style = MaterialTheme.typography.titleLarge)
        Text("Potatoflix can't reach the internet. Check your Wi-Fi, then try again. Downloaded titles still play.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = Palette.muted)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Try again", Icons.Filled.Refresh, compact = true) { onRetry() }
            PillButton("Downloads", Icons.Filled.Download, primary = false, compact = true) { openLibraryDownloads = true; Nav.select(Tab.LIBRARY) }
        }
    }
}
