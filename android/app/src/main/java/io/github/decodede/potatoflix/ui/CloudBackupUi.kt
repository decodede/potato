package io.github.decodede.potatoflix.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.decodede.potatoflix.App
import io.github.decodede.potatoflix.CloudBackup
import kotlinx.coroutines.launch

/** Google Drive backup: what's open right now (the dialogs can be opened from Settings or the first-run screen). */
object CloudUi {
    var signingIn by mutableStateOf(false)
    /** Every device's backup in the account, to pick one to restore (the phone's on the TV…). */
    var choosing by mutableStateOf<List<CloudBackup.Remote>?>(null)
    /** The backup picked, waiting for "Restore". */
    var confirm by mutableStateOf<CloudBackup.Remote?>(null)
    var restored by mutableStateOf(false)

    /** Sign in (if needed), then list the backups in the account. */
    fun restoreFromDrive() {
        if (!CloudBackup.state.value.signedIn) { signingIn = true; return }
        App.scope.launch {
            runCatching { CloudBackup.backups() }
                .onSuccess { list -> if (list.isEmpty()) App.toast("There's no backup in this Google account yet") else choosing = list }
                .onFailure { App.toast("Couldn't reach Google Drive: ${it.message}") }
        }
    }
}

fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "not yet"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** "Lenovo TB522FU · 3 min ago · Potatoflix 8.0.1" */
private fun CloudBackup.Remote.describe(): String =
    listOfNotNull(ago(time), app?.let { "Potatoflix $it" }).joinToString(" · ")

private fun date(iso: String): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}.getOrDefault(iso)

/** Settings → Updates and backup → Google Drive. */
@Composable
internal fun CloudBackupSection() {
    val state by CloudBackup.state.collectAsState()
    Heading("Google Drive backup")
    if (!CloudBackup.configured) {
        Hint("Google sign-in isn't set up in this build of the app.")
        return
    }
    if (!state.signedIn) {
        Hint("Sign in with your Google account to keep this backup in your Google Drive, automatically, and restore it on " +
            "another device or after reinstalling. It goes in a \"Potatoflix backups\" folder: Potatoflix only sees the files it made, never your other files.")
        PillButton("Sign in with Google", Icons.Filled.CloudUpload, compact = true, modifier = Modifier.padding(top = 6.dp)) { CloudUi.signingIn = true }
        return
    }
    Text("Signed in as ${state.email ?: "your Google account"}", style = MaterialTheme.typography.bodyLarge)
    Hint("Last backup: ${ago(state.lastBackup)}" + (state.busy?.let { " · $it" } ?: "") + (state.error?.let { " · Problem: $it" } ?: ""))
    var auto by remember { mutableStateOf(CloudBackup.auto) }
    SwitchRow("Back up automatically", "When something changed: every 5 minutes, and when you leave the app. Each device keeps its own backup.", auto) { auto = it; CloudBackup.auto = it }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        PillButton("Back up now", Icons.Filled.CloudUpload, compact = true) {
            App.scope.launch {
                runCatching { CloudBackup.backupNow() }.onSuccess { App.toast("Backed up to Google Drive") }
                    .onFailure { App.toast("Couldn't back up: ${it.message}") }
            }
        }
        PillButton("Restore", Icons.Filled.CloudDownload, primary = false, compact = true) { CloudUi.restoreFromDrive() }
    }
    PillButton("Sign out", Icons.Filled.Logout, primary = false, compact = true, modifier = Modifier.padding(top = 6.dp)) {
        App.scope.launch { CloudBackup.signOut(); App.toast("Signed out. The backup stays in your Google Drive.") }
    }
}

/** The Google Drive dialogs, shown over any screen (see AppRoot). */
@Composable
fun CloudDialogs() {
    if (CloudUi.signingIn) SignInDialog()
    CloudUi.choosing?.let { BackupPicker(it) }
    CloudUi.confirm?.let { RestoreDialog(it) }
    if (CloudUi.restored) {
        val context = LocalContext.current
        SimpleDialog("Restored", { CloudUi.restored = false }, confirm = "Restart now", onConfirm = { restartApp(context) }) {
            Hint("Your backup is back, and the extensions in it were downloaded. Restart the app to finish.")
        }
    }
}

@Composable
private fun SignInDialog() {
    var signIn by remember { mutableStateOf<CloudBackup.SignIn?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { CloudBackup.startSignIn() }.onSuccess { s ->
            signIn = s
            runCatching { CloudBackup.finishSignIn(s) }.onSuccess {
                CloudUi.signingIn = false
                App.toast("Signed in as ${CloudBackup.state.value.email ?: "your Google account"}")
                // Backups of other devices (the phone's, on the TV) are offered; otherwise this device's is saved now.
                App.scope.launch {
                    val list = runCatching { CloudBackup.backups() }.getOrDefault(emptyList())
                    if (list.any { !it.mine }) CloudUi.choosing = list
                    else runCatching { CloudBackup.backupNow() }.onSuccess { App.toast("Backed up to Google Drive") }
                }
            }.onFailure { error = it.message }
        }.onFailure { error = it.message }
    }
    SimpleDialog("Sign in with Google", { CloudUi.signingIn = false }) { SignInBody(signIn, error) }
}

/** The code to enter at google.com/device, with a QR code for the page. */
@Composable
internal fun SignInBody(signIn: CloudBackup.SignIn?, error: String?, tv: Boolean = isTv) {
        val s = signIn
        when {
            error != null -> Hint("Couldn't sign in: $error")
            s == null -> Hint("Getting a code from Google…")
            // A phone can't scan its own screen: copy the code and open Google's page in the browser, then come back.
            !tv -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your code", style = MaterialTheme.typography.bodyMedium)
                Text(s.userCode, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                    color = Palette.accent)
                PillButton("Copy the code and open Google", Icons.Filled.OpenInBrowser, compact = true) {
                    runCatching {
                        (App.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Google code", s.userCode))
                        App.context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(s.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { App.toast("Open ${s.url} in a browser and enter ${s.userCode}") }
                }
                Hint("Paste the code there, choose your Google account and allow Potatoflix to keep its backups in your Drive. " +
                    "Then come back: this screen moves on by itself.")
                Hint("Or on another phone or computer, open ${s.url.removePrefix("https://").removePrefix("www.")} and enter the code.")
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(s.url) { qrBitmap(s.url, 360).asImageBitmap() }
                Image(qr, "QR code for ${s.url}", Modifier.size(150.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("On your phone or computer, open", style = MaterialTheme.typography.bodyMedium)
                    Text(s.url.removePrefix("https://").removePrefix("www."), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("and enter this code:", style = MaterialTheme.typography.bodyMedium)
                    Text(s.userCode, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                        color = Palette.accent)
                    Hint("Then choose your Google account and allow Potatoflix to keep its backup in your Drive. This screen moves on by itself.")
                }
            }
        }
}

/** Every device's backup in the account: pick one to restore, or delete an old device's. */
@Composable
private fun BackupPicker(list: List<CloudBackup.Remote>) {
    var backups by remember(list) { mutableStateOf(list) }
    SimpleDialog("Restore a backup", { CloudUi.choosing = null }) {
        Hint("Each device that's signed in keeps its own backup. Pick the one to copy to this device.")
        backups.forEach { r ->
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().card(RoundedCornerShape(12.dp), focusScale = 1.02f) { CloudUi.choosing = null; CloudUi.confirm = r }
                    .background(Palette.surfaceHigh, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Devices, null, tint = Palette.muted, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text((r.device ?: "A device") + if (r.mine) " (this device)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(r.describe(), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                Icon(Icons.Filled.Delete, "Delete this backup", tint = Palette.muted, modifier = Modifier.size(36.dp).card(RoundedCornerShape(50)) {
                    App.scope.launch {
                        runCatching { CloudBackup.delete(r) }.onSuccess { backups = backups - r; App.toast("Backup deleted") }
                            .onFailure { App.toast("Couldn't delete: ${it.message}") }
                    }
                }.padding(7.dp))
            }
        }
        if (backups.isEmpty()) Hint("No backups left in this Google account.")
    }
}

@Composable
private fun RestoreDialog(remote: CloudBackup.Remote) {
    val context = LocalContext.current
    SimpleDialog("Restore this backup?", { CloudUi.confirm = null }, confirm = "Restore", onConfirm = {
        CloudUi.confirm = null
        App.toast("Restoring…")
        App.scope.launch {
            runCatching { CloudBackup.restore(remote) }
                .onSuccess { CloudUi.restored = true }
                .onFailure { App.toast("Couldn't restore: ${it.message}") }
        }
    }) {
        Hint("The backup" + (remote.device?.let { " from $it" } ?: "") + (if (remote.mine) " (this device)" else "") +
            ", saved ${date(remote.modified)}" + (remote.app?.let { " (Potatoflix $it)" } ?: "") + ".")
        Hint("Restoring replaces this device's settings, profiles, My list, history, repositories and extensions with the backup's. " +
            "The extensions are downloaded again.")
    }
}
