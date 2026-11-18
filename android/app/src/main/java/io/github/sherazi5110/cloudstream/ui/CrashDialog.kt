package io.github.sherazi5110.cloudstream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.CrashReport

/** After Potatoflix closed because of an error: what happened, to copy, share or photograph for a report. */
@Composable
fun CrashDialog() {
    var report by remember { mutableStateOf(CrashReport.pending) }
    val text = report ?: return
    val context = LocalContext.current
    fun close() { CrashReport.dismiss(); report = null }
    SimpleDialog(
        "Potatoflix closed unexpectedly", ::close, confirm = "Share", onConfirm = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { context.startActivity(Intent.createChooser(send, "Send the error report")) }
                .onFailure { App.toast("No app to share with here: use Copy, or take a photo of this screen") }
        },
        extra = {
            PillButton("Report on GitHub", null, primary = false, compact = true) { reportOnGitHub(context, text) }
            PillButton("Copy", null, primary = false, compact = true) {
                runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Potatoflix error report", text))
                    App.toast("Copied")
                }
            }
        },
    ) {
        Hint("Here's what went wrong. Report it on GitHub (the report is filled in: just press Submit), or copy or share it and send it to the developer, so it can be fixed.")
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, style = MaterialTheme.typography.bodySmall)
    }
}

/** The QR code for a GitHub report on TVs (no browser there): scan it with a phone. */
private var githubQr by mutableStateOf<String?>(null)

/** Shows [url] (a GitHub new-issue link) as a QR code to scan with a phone. */
fun showGitHubQr(url: String) { githubQr = url }

/** Opens GitHub's "new issue" page with [report] filled in; on a TV, shows it as a QR code for a phone instead. */
fun reportOnGitHub(context: Context, report: String?) {
    if (!App.isTv) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CrashReport.issueUrl(report))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
    // A QR code holds about 2,900 characters: the start of the report (the cause comes first).
    githubQr = CrashReport.issueUrl(report, maxReport = 1100)
}

@Composable
fun GitHubReportQr() {
    val url = githubQr ?: return
    SimpleDialog("Report on GitHub", { githubQr = null }) {
        Hint("Scan this with your phone: GitHub's page opens with the report filled in. Press Submit there (you need a GitHub account).")
        val qr = remember(url) { qrBitmap(url, 720).asImageBitmap() }
        androidx.compose.foundation.Image(qr, "QR code for the GitHub report", androidx.compose.ui.Modifier
            .padding(top = 12.dp).size(300.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(10.dp)).padding(10.dp))
    }
}
