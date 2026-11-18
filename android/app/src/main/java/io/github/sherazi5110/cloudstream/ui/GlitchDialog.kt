package io.github.sherazi5110.cloudstream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.GlitchReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Report a glitch": the picture of the screen, a box for what went wrong, and what the app adds (screen, last
 * steps, its log). Send on GitHub opens the filled-in issue (a QR code on TVs); the person submits it there.
 */
@Composable
fun GlitchDialog() {
    val d = GlitchReport.draft ?: return
    val context = LocalContext.current
    var what by remember(d) { mutableStateOf("") }
    var attach by remember(d) { mutableStateOf(d.shot != null && !App.isTv) }
    var log by remember(d) { mutableStateOf<String?>(null) }
    var showDetails by remember(d) { mutableStateOf(false) }
    LaunchedEffect(d) { log = withContext(Dispatchers.IO) { GlitchReport.logTail() } }
    fun close() { GlitchReport.draft = null }
    fun report(saved: String?) = GlitchReport.reportText(d, saved, log.orEmpty())
    SimpleDialog(
        "Report a glitch", ::close, confirm = "Send", onConfirm = {
            val saved = if (attach) d.shot?.let { GlitchReport.save(it) } else null
            if (attach && saved == null) App.toast("Couldn't save the screenshot")
            sendGlitch(context, GlitchReport.issueUrl(what, report(saved), saved != null), GlitchReport.issueUrl(what, report(saved), saved != null, maxReport = 900))
            close()
        },
        extra = {
            PillButton("Copy", null, primary = false, compact = true) {
                runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Potatoflix glitch report", (what.takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") + report(null)))
                    App.toast("Copied")
                }
            }
        },
    ) {
        Hint(if (App.isTv) "Say what looks wrong, then scan the QR code with your phone to post it on GitHub. Nothing is sent until you press Submit there."
            else "Say what looks wrong. GitHub opens with everything below filled in; you add the screenshot there and press Submit. Nothing is sent by the app.")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
            placeholder = { Text("What went wrong? e.g. \"The play button is hidden behind the title\"") })
        d.shot?.let { shot ->
            Spacer(Modifier.height(12.dp))
            val image = remember(shot) { shot.asImageBitmap() }
            Image(image, "Screenshot of the screen", Modifier.fillMaxWidth().heightIn(max = 200.dp)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
            if (!App.isTv) SwitchRow("Save this screenshot", "To Pictures → Potatoflix, so you can add it to the report", attach) { attach = it }
        }
        Spacer(Modifier.height(4.dp))
        SwitchRow("Show what the app adds", d.screen, showDetails) { showDetails = it }
        if (showDetails) Column(Modifier.padding(top = 4.dp)) {
            Text(log?.let { report(if (attach) "Pictures/Potatoflix/glitch-….png" else null) } ?: "Reading the app's log…",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Opens the issue page; on a TV (no browser) shows the shorter link as a QR code for a phone instead. */
private fun sendGlitch(context: Context, url: String, shortUrl: String) {
    if (!App.isTv) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
    showGitHubQr(shortUrl)
}
