package io.github.decodede.potatoflix

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.decodede.potatoflix.ui.Nav
import io.github.decodede.potatoflix.ui.Screen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Report a glitch": a picture of the screen, which screen it was, the last things done in the app and the end of the
 * app's error log, put into GitHub's new-issue page for the person to read and submit. Nothing is sent by the app.
 */
object GlitchReport {
    /** The last things done (screens opened, sources tried, messages shown), oldest first. */
    private val crumbs = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** A report being written: shown by GlitchDialog. */
    class Draft(val screen: String, val steps: List<String>, val shot: Bitmap?, val crash: String? = null)
    var draft by mutableStateOf<Draft?>(null)

    /** Remembers one step ("Opened Details: Dune (HDHub4U)"). Cheap; safe from any thread. */
    fun step(text: String) {
        synchronized(crumbs) {
            crumbs.addLast("${time.format(Date())} ${text.take(160)}")
            while (crumbs.size > 20) crumbs.removeFirst()
        }
    }

    fun steps(): List<String> = synchronized(crumbs) { crumbs.toList() }

    /** A screen in words, with the title and extension it's about. */
    fun describe(screen: Screen?): String = when (screen) {
        null -> "Tab: ${Nav.tab.label}"
        is Screen.Details -> "Details: ${screen.name} (${screen.apiName})"
        is Screen.Player -> "Player: ${screen.response.name}" + (screen.playable.label.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") +
            " (${screen.api.name})"
        is Screen.Catalog -> "Catalog: ${screen.title} (${screen.apiName})"
        is Screen.SettingsPage -> "Settings: ${screen.section.lowercase()}"
        is Screen.Repository -> "Repository: ${screen.name}"
        Screen.AddFromPhone -> "Add from phone"
        Screen.ProviderTests -> "Test all extensions"
    }

    /** Starts a report about what [activity] shows now: takes the picture first, then opens the form. */
    fun start(activity: Activity?) {
        val screen = describe(Nav.top)
        val steps = steps()
        capture(activity) { shot -> draft = Draft(screen, steps, shot) }
    }

    /** A picture of the window (TextureView video included), at most 1280 pixels wide; null if it can't be taken. */
    private fun capture(activity: Activity?, done: (Bitmap?) -> Unit) {
        val window = activity?.window
        val view = window?.decorView
        if (view == null || view.width <= 0 || view.height <= 0) { done(null); return }
        val full = runCatching { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: run { done(null); return }
        fun finish(b: Bitmap?) {
            val scaled = b?.let { if (it.width > 1280) Bitmap.createScaledBitmap(it, 1280, it.height * 1280 / it.width, true).also { s -> if (s !== it) it.recycle() } else it }
            done(scaled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                PixelCopy.request(window, full, { result -> finish(if (result == PixelCopy.SUCCESS) full else null) }, Handler(Looper.getMainLooper()))
            }.onFailure { finish(runCatching { view.draw(android.graphics.Canvas(full)); full }.getOrNull()) }
        } else finish(runCatching { view.draw(android.graphics.Canvas(full)); full }.getOrNull())
    }

    /**
     * Saves [shot] where the phone's photo picker finds it (Pictures/Potatoflix), so it can be added to the GitHub
     * issue. Returns where it went, for the report text.
     */
    fun save(shot: Bitmap): String? = runCatching {
        val name = "glitch-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Potatoflix")
            }
            val resolver = App.context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return null
            "Pictures/Potatoflix/$name"
        } else {
            val dir = File(App.context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Potatoflix").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        }
    }.getOrNull()

    /** The last warnings and errors this app wrote to the system log (its own lines only), with secrets blanked. */
    fun logTail(lines: Int = 30): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "time", "--pid=${android.os.Process.myPid()}", "*:W")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        out.filter { it.isNotBlank() && !it.startsWith("-----") }.takeLast(lines).joinToString("\n") { redact(it).take(300) }
    }.getOrDefault("")

    private val secrets = listOf(
        Regex("""(?i)(access_token|refresh_token|id_token|client_secret|api[_-]?key|password|token)(["'=:\s]+)[^\s"'&,]+"""),
        Regex("""(?i)(authorization:\s*\w+\s+)\S+"""),
        Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""),
    )

    /** Blanks tokens, keys, passwords and email addresses. */
    fun redact(text: String): String {
        var t = text
        t = secrets[0].replace(t) { "${it.groupValues[1]}${it.groupValues[2]}…" }
        t = secrets[1].replace(t) { "${it.groupValues[1]}…" }
        t = secrets[2].replace(t, "…@…")
        return t
    }

    /** The text that goes into the GitHub issue (under the person's own words). */
    fun reportText(d: Draft, screenshot: String?, log: String): String = buildString {
        d.crash?.let { appendLine(it.trim()); appendLine() }
        appendLine(CrashReport.header())
        appendLine("Screen: ${d.screen}")
        if (screenshot != null) appendLine("Screenshot saved on the device: $screenshot")
        if (d.steps.isNotEmpty()) {
            appendLine()
            appendLine("Last steps:")
            d.steps.takeLast(12).forEach { appendLine("  $it") }
        }
        if (log.isNotBlank()) {
            appendLine()
            appendLine("App log (warnings and errors):")
            appendLine(log)
        }
    }

    /** GitHub's new-issue page for a glitch: [what] (the person's words) first, then [report]. */
    fun issueUrl(what: String, report: String, screenshotSaved: Boolean, maxReport: Int = 5000): String {
        val title = "Glitch: " + (what.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.take(70) ?: report.lineSequence().drop(1).firstOrNull()?.removePrefix("Screen: ")?.take(70) ?: "something looks wrong")
        val body = buildString {
            appendLine("**What went wrong**")
            appendLine(what.ifBlank { "(describe what you saw and what you expected)" })
            appendLine()
            if (screenshotSaved) {
                appendLine("**Screenshot**: add it here: drag it in, or tap below and choose it from Pictures → Potatoflix.")
                appendLine()
            }
            appendLine("**Details from the app**")
            appendLine("```")
            appendLine(report.take(maxReport))
            appendLine("```")
        }
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        return "https://github.com/decodede/potato/issues/new?title=${enc(title)}&body=${enc(body)}&labels=glitch"
    }
}
