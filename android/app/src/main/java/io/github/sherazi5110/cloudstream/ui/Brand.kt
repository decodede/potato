package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.R

/** The Potatoflix logo: the potato-and-remote icon, a rounded tile. */
@Composable
fun Logo(size: Dp = 36.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.app_icon), "Potatoflix", Modifier.size(size), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    }
}
