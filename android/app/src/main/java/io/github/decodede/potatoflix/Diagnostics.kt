package io.github.decodede.potatoflix

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Runs the same steps the UI does (home -> search -> load -> sources) against one
 * provider and reports where it breaks. This is what users attach to bug reports.
 */
object ProviderTest {
    data class Step(val name: String, val ok: Boolean, val detail: String, val ms: Long)

    fun run(api: MainAPI): Flow<Step> = flow {
        var sample: SearchResponse? = null

        suspend fun <T> timed(block: suspend () -> T): Pair<Result<T>, Long> {
            val start = System.currentTimeMillis()
            val result = runCatching { block() }
            return result to System.currentTimeMillis() - start
        }

        if (api.hasMainPage && api.mainPage.isNotEmpty()) {
            val (r, ms) = timed { Engine.homePage(api, api.mainPage.first(), 1) }
            val items = r.getOrNull()?.items?.flatMap { it.list }.orEmpty()
            sample = items.firstOrNull()
            emit(Step("Home page", items.isNotEmpty(), r.exceptionOrNull()?.toString() ?: "${items.size} items in “${api.mainPage.first().name}”", ms))
        }

        val query = sample?.name?.split(' ')?.firstOrNull { it.length > 2 } ?: "the"
        val (s, sms) = timed { withTimeoutOrNull(45_000) { api.search(query, 1)?.items }.orEmpty() }
        val results = s.getOrNull().orEmpty()
        emit(Step("Search “$query”", results.isNotEmpty(), s.exceptionOrNull()?.toString() ?: "${results.size} results", sms))
        sample = sample ?: results.firstOrNull()

        val item = sample ?: run {
            emit(Step("Details", false, "Nothing to open: home and search returned no items", 0))
            return@flow
        }
        val (l, lms) = timed { Engine.load(api, item.url) }
        val response = l.getOrNull()
        val playables = response?.let { Engine.playables(it) }.orEmpty()
        emit(Step("Details “${item.name}”", response != null && playables.isNotEmpty(),
            l.exceptionOrNull()?.toString() ?: if (response == null) "returned nothing" else "${playables.size} playable item(s)", lms))
        val first = playables.firstOrNull() ?: return@flow

        val start = System.currentTimeMillis()
        var links = 0
        var subs = 0
        var error: Throwable? = null
        var timedOut = false
        Engine.links(api, first.data).collect {
            when (it) {
                is LinkEvent.Link -> links++
                is LinkEvent.Subtitle -> subs++
                is LinkEvent.Done -> { error = it.error; timedOut = it.timedOut }
            }
        }
        val detail = buildString {
            append("$links source(s), $subs subtitle(s)")
            if (timedOut) append(" · stopped at the time limit")
            error?.let { append(" · error: $it") }
        }
        emit(Step("Sources for “${first.label}”", links > 0, detail, System.currentTimeMillis() - start))
    }
}

/** The app's own event log, kept in memory (ring buffer), shown in Settings → About → Logs, like the official app's logs. */
object AppLog {
    private const val CAP = 400
    private val lines = java.util.ArrayDeque<String>(CAP + 1)
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    /** Bumped on every line, so the Logs screen recomposes. */
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    val version: Int get() = state.intValue

    private fun add(level: Char, tag: String, msg: String?, t: Throwable?) {
        val raw = buildString {
            append(timeFormat.format(java.util.Date())); append(' '); append(level); append('/').append(tag); append(": "); append(msg.orEmpty())
            t?.let { append(" · ").append(it.message ?: it.toString()) }
        }
        synchronized(lines) {
            while (lines.size >= CAP) lines.removeFirst()
            lines.addLast(raw)
        }
        state.intValue++
    }

    fun i(tag: String, msg: String?) = add('I', tag, msg, null)
    fun w(tag: String, msg: String?, t: Throwable? = null) = add('W', tag, msg, t)
    fun e(tag: String, msg: String?, t: Throwable? = null) = add('E', tag, msg, t)

    fun all(): List<String> = synchronized(lines) { lines.toList() }
    fun clear() { synchronized(lines) { lines.clear() }; state.intValue++ }
}
