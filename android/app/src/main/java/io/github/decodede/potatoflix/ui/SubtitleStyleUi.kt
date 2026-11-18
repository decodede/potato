package io.github.decodede.potatoflix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.decodede.potatoflix.Settings
import io.github.decodede.potatoflix.player.PlayerController
import io.github.decodede.potatoflix.player.SubtitleStyle

/** The player's side panel frame: dims the video, closes on a tap outside. */
@Composable
private fun SidePanel(title: String, modifier: Modifier, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(modifier.fillMaxHeight().width(380.dp).glassPanel(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp), blur = false)
            .pointerInput(Unit) { detectTapGestures { } }.padding(vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            content()
        }
    }
}

/** A choice button: focusable on TV, ticked when chosen. */
@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, swatch: Color? = null, onClick: () -> Unit) {
    Row(
        modifier.card(RoundedCornerShape(20.dp), focusScale = 1.05f, onClick = onClick)
            .background(if (selected) Palette.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
            .border(1.dp, if (selected) Palette.accent else Color.Transparent, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(Modifier.size(14.dp).background(swatch, CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (selected && swatch == null) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Palette.accent) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, value: T, swatches: Boolean = false, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) ->
                Choice(label, v == value, swatch = if (swatches) Color(0xFF000000 or SubtitleStyle.rgb(v.toString()).toLong()) else null) { onPick(v) }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .card(RoundedCornerShape(10.dp), focusScale = 1.02f) { onChange(!on) }.padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
        Box(Modifier.size(44.dp, 24.dp).background(if (on) Palette.accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(3.dp).size(18.dp).background(Color.White, CircleShape))
        }
    }
}

/** How the subtitles will look, drawn the way the player's SubtitleView draws them, over a dark "scene". */
@Composable
private fun Preview(look: SubtitleStyle.Look, clean: SubtitleStyle.Clean) {
    val text = buildString {
        if (!clean.noCaptions) append("[door creaks]\n")
        append("Is anyone there? I heard something.")
    }.let { if (clean.uppercase) it.uppercase() else it }
    val color = Color(0xFF000000 or SubtitleStyle.rgb(look.color).toLong())
    val outline = Color(0xFF000000 or SubtitleStyle.rgb(look.outlineColor).toLong())
    val family = when (look.font) { "SERIF" -> FontFamily.Serif; "MONO" -> FontFamily.Monospace; "CONDENSED" -> FontFamily.SansSerif; else -> FontFamily.Default }
    val shadow = look.edge == "SHADOW" || look.edge == "BOTH"
    val base = TextStyle(
        fontSize = SubtitleStyle.previewSp(look.size).sp, fontFamily = family,
        fontWeight = if (look.bold) FontWeight.Bold else FontWeight.Medium, fontStyle = if (clean.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
        letterSpacing = if (look.font == "CONDENSED") (-0.5).sp else 0.sp,
    )
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(150.dp)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF1A2230), Color(0xFF0D1118))), RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = (look.height * 14).dp)) {
            val bg = if (look.background) Color(0xFF000000 or SubtitleStyle.rgb(look.backgroundColor).toLong()).copy(alpha = look.backgroundOpacity / 100f) else Color.Transparent
            Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
                val outlined = look.edge == "OUTLINE" || look.edge == "BOTH"
                if (outlined) Text(text, Modifier.fillMaxWidth(), style = base.copy(color = outline, drawStyle = Stroke(width = look.outline * 1.6f)))
                Text(text, Modifier.fillMaxWidth(), style = base.copy(color = color,
                    shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.8f), Offset(3f, 3f), 4f) else null))
            }
        }
    }
}

/**
 * Subtitle style, inside the player: size, colour, font, bold/italic, edge, background, height and the
 * clean-ups. The look is applied live (ExoPlayer's SubtitleView); clean-up changes reload the subtitle file.
 */
@Composable
fun SubtitleStylePanel(c: PlayerController, onClose: () -> Unit) =
    SubtitleStylePanel(onApply = { cleanChanged -> if (cleanChanged) c.reloadSubtitle() }, onClose = onClose)

/** [onApply] runs when the panel closes (true: the clean-up settings changed). */
@Composable
internal fun SubtitleStylePanel(onApply: (Boolean) -> Unit, onClose: () -> Unit) {
    var look by remember { mutableStateOf(SubtitleStyle.current()) }
    var clean by remember { mutableStateOf(SubtitleStyle.currentClean()) }
    val startClean = remember { clean }
    DisposableEffect(Unit) {
        onDispose {
            onApply(clean != startClean)
        }
    }
    fun set(change: SubtitleStyle.Look.() -> SubtitleStyle.Look) {
        look = look.change()
        Settings.subtitleSize = look.size; Settings.subtitleColor = look.color; Settings.subtitleFont = look.font
        Settings.subtitleBold = look.bold; Settings.subtitleEdge = look.edge; Settings.subtitleOutline = look.outline
        Settings.subtitleOutlineColor = look.outlineColor; Settings.subtitleBackground = look.background
        Settings.subtitleBackgroundColor = look.backgroundColor; Settings.subtitleBackgroundOpacity = look.backgroundOpacity
        Settings.subtitleHeight = look.height
    }
    fun setClean(change: SubtitleStyle.Clean.() -> SubtitleStyle.Clean) {
        clean = clean.change()
        Settings.subtitleNoCaptions = clean.noCaptions; Settings.subtitleNoAds = clean.noAds
        Settings.subtitleUppercase = clean.uppercase; Settings.subtitleItalic = clean.italic
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Subtitle style", Modifier, onClose) {
        Preview(look, clean)
        Text("Applies when you close this panel (the video reopens where it was).",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 20.dp))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Box(Modifier.focusRequester(first)) {
                ChoiceRow("Size", SubtitleStyle.sizes, look.size) { v -> set { copy(size = v) } }
            }
            ChoiceRow("Text colour", SubtitleStyle.colors.dropLast(1), look.color, swatches = true) { v -> set { copy(color = v) } }
            ChoiceRow("Font", SubtitleStyle.fonts, look.font) { v -> set { copy(font = v) } }
            Toggle("Bold", "Thicker letters", look.bold) { v -> set { copy(bold = v) } }
            Toggle("Italic", "Slanted letters (subtitle files)", clean.italic) { v -> setClean { copy(italic = v) } }
            Toggle("All capitals", "SHOUTY BUT EASY TO READ (subtitle files)", clean.uppercase) { v -> setClean { copy(uppercase = v) } }
            ChoiceRow("Edge", SubtitleStyle.edges, look.edge) { v -> set { copy(edge = v) } }
            if (look.edge == "OUTLINE" || look.edge == "BOTH") {
                ChoiceRow("Outline size", SubtitleStyle.outlines, look.outline) { v -> set { copy(outline = v) } }
                ChoiceRow("Outline colour", listOf("#000000" to "Black", "#333333" to "Dark grey", "#ffffff" to "White", "#1a237e" to "Navy"),
                    look.outlineColor, swatches = true) { v -> set { copy(outlineColor = v) } }
            }
            Toggle("Background box", "A box behind the text", look.background) { v -> set { copy(background = v) } }
            if (look.background) {
                ChoiceRow("Background colour", listOf("#000000" to "Black", "#333333" to "Grey", "#1a237e" to "Navy", "#ffffff" to "White"),
                    look.backgroundColor, swatches = true) { v -> set { copy(backgroundColor = v) } }
                ChoiceRow("Background opacity", listOf(30 to "30%", 60 to "60%", 80 to "80%", 100 to "Solid"), look.backgroundOpacity) { v -> set { copy(backgroundOpacity = v) } }
            }
            ChoiceRow("Height on screen", SubtitleStyle.heights, look.height) { v -> set { copy(height = v) } }
            Toggle("Remove closed captions", "Hides [door knocks], (laughs), ♪ lyrics and NAME: labels", clean.noCaptions) { v -> setClean { copy(noCaptions = v) } }
            Toggle("Remove ads", "Hides \"Subtitles by…\" and website lines", clean.noAds) { v -> setClean { copy(noAds = v) } }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Choice("Reset to default", false) {
                    look = SubtitleStyle.Look(); set { this }
                    clean = SubtitleStyle.Clean(); setClean { this }
                }
            }
            Text("Subtitle language is picked automatically from Settings → Player → Subtitles.\n" +
                "Italic, capitals and the clean-ups change subtitle files; subtitles built into the video keep their own text.",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** "+0.3 s" / "−1.2 s" / "In sync". */
internal fun delayText(ms: Long): String = when {
    ms == 0L -> "In sync"
    else -> (if (ms > 0) "+" else "−") + String.format(java.util.Locale.US, "%.1f s", kotlin.math.abs(ms) / 1000.0)
}

/** Subtitle sync: move the subtitles earlier or later to match the voices. */
@Composable
internal fun SyncPanel(value: Long, set: (Long) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Sync subtitles", Modifier, onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                "Subtitles out of step with the voices? Move them earlier or later until they match.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.height(18.dp))
            Text(delayText(value), style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(
                when {
                    value == 0L -> "Not moved"
                    value > 0 -> "Subtitles show later"
                    else -> "Subtitles show earlier"
                },
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Text("Earlier (subtitles come too late)", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("− 5 s", false) { set(value - 5000) }
                Choice("− 1 s", false) { set(value - 1000) }
                Choice("− 0.1 s", false, Modifier.focusRequester(first)) { set(value - 100) }
            }
            Spacer(Modifier.height(14.dp))
            Text("Later (subtitles come too early)", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("+ 0.1 s", false) { set(value + 100) }
                Choice("+ 1 s", false) { set(value + 1000) }
                Choice("+ 5 s", false) { set(value + 5000) }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Reset", value == 0L) { set(0) }
                Choice("Done", false) { onClose() }
            }
            Spacer(Modifier.height(14.dp))
            Text("Keeps playing while you adjust. Applies to this video; every new video starts in sync.",
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
        }
    }
}
