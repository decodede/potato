package io.github.decodede.potatoflix

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/**
 * Backup to Google Drive. Sign-in uses Google's code sign-in for TVs and other devices ("OAuth 2.0 for TV and
 * limited-input devices"): this app shows a code, you enter it at google.com/device on your phone or computer.
 * The same works on phones, Android TV and Windows/Linux.
 *
 * The backup (the same file as Settings → Backup) is kept in a "Potatoflix backups" folder in My Drive (scope
 * drive.file: the app sees only the files it created itself, never the rest of the Drive; Google's code sign-in
 * doesn't allow the hidden app folder, drive.appdata). Each device keeps its own backup file, so a
 * phone and a TV on the same account don't overwrite each other, and Restore lists every device's backup (restore
 * the phone's on the TV). With automatic backup on, it's uploaded when something changed (checked every 5 minutes
 * and when the app goes to the background).
 *
 * The Google client id comes from the build (GitHub secrets GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET, see
 * docs/google-drive-setup.md). Builds without it show that Google sign-in isn't set up.
 */
object CloudBackup {
    const val FILE_PREFIX = "potatoflix-backup"
    /** Automatic backup: checked this often, uploaded only when something changed. */
    const val AUTO_INTERVAL_MS = 5 * 60_000L
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    /** The folder in My Drive the backups go to (created with the first backup). */
    const val FOLDER = "Potatoflix backups"

    /** Google's addresses; tests point them at a local server. */
    class Endpoints(
        val deviceCode: String = "https://oauth2.googleapis.com/device/code",
        val token: String = "https://oauth2.googleapis.com/token",
        val revoke: String = "https://oauth2.googleapis.com/revoke",
        val drive: String = "https://www.googleapis.com/drive/v3",
        val upload: String = "https://www.googleapis.com/upload/drive/v3",
        /** Google's older sign-in address, used when the one above can't be found (see [fallbackUrl]). */
        val authFallback: String? = "https://accounts.google.com/o/oauth2",
    )
    @Volatile var endpoints = Endpoints()

    /** From the build; tests and developers can set them (see docs/google-drive-setup.md). */
    @Volatile var clientId: String = BuildConfig.GOOGLE_CLIENT_ID
    @Volatile var clientSecret: String = BuildConfig.GOOGLE_CLIENT_SECRET
    val configured: Boolean get() = clientId.isNotBlank()

    /** This device's account. Kept out of backups (see Backup.skip), so a restore never moves it. */
    private val prefs: SharedPreferences get() = App.context.getSharedPreferences("cloud_backup", 0)

    data class State(
        val signedIn: Boolean,
        val email: String?,
        val lastBackup: Long,
        val busy: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state
    private fun readState(busy: String? = null, error: String? = null) = State(
        prefs.getString("refresh", null) != null, prefs.getString("email", null), prefs.getLong("last_backup", 0), busy, error,
    )
    private fun publish(busy: String? = null, error: String? = null) { _state.value = readState(busy, error) }

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    /** This installation's own backup file in Drive (never part of a backup, so a restore keeps it). */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).commit()
        }

    // ------------------------------------------------------------------ sign-in

    /** A code waiting to be entered at [url]. */
    data class SignIn(val userCode: String, val url: String, val deviceCode: String, val intervalSeconds: Int, val expiresAt: Long)

    class CloudException(message: String) : Exception(message)

    /** The app's connection (and DNS setting), with [RescueDns] for when that DNS has no answer for Google. */
    @Volatile private var built: Pair<OkHttpClient, OkHttpClient>? = null
    private val client: OkHttpClient get() {
        val base = app.baseClient
        built?.takeIf { it.first === base }?.let { return it.second }
        return base.newBuilder().dns(RescueDns(base.dns)).build().also { built = base to it }
    }
    private val json = "application/json; charset=UTF-8".toMediaType()

    private suspend fun call(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        fun send(r: Request) = client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
        try {
            send(request)
        } catch (e: java.net.UnknownHostException) {
            // Sign-in can also go through accounts.google.com, which any device that signs in to Google can find.
            val other = fallbackUrl(request.url.toString(), endpoints)
            val second = other?.let { runCatching { send(request.newBuilder().url(it).build()) } }
            second?.getOrNull()?.let { return@withContext it }
            val tried = (second?.exceptionOrNull() as? java.net.UnknownHostException)?.message?.let { "; $it" } ?: ""
            // Is it only Google, or does this app reach nothing? (Android answers "No address associated with
            // hostname" for every name when the app is blocked from the internet: a firewall, data limits.)
            val others = runCatching { Dns.SYSTEM.lookup("github.com"); "other sites are found" }.getOrElse { "other sites aren't found either" }
            val advice = if (others.startsWith("other sites are"))
                "Something on this device or network blocks Google: an ad blocker, a VPN or firewall app, Private DNS or the router. " +
                    "Allow googleapis.com and accounts.google.com there, or try another network."
            else "This app may be blocked from the internet on this device: check its settings (data usage, background data), " +
                "a firewall or ad-block app, or the network."
            throw CloudException("Couldn't reach Google: ${e.message}$tried; $others. $advice")
        }
    }

    /** The same sign-in step at Google's older address (device code, token, revoke), or null. */
    internal fun fallbackUrl(url: String, e: Endpoints = endpoints): String? {
        val base = e.authFallback ?: return null
        return when (url) {
            e.deviceCode -> "$base/device/code"
            e.token -> "$base/token"
            e.revoke -> "$base/revoke"
            else -> null
        }
    }

    /**
     * Looks a name up with the app's DNS first; when that has no answer (Private DNS, an ad blocker or a router
     * filter: "Unable to resolve host oauth2.googleapis.com", reported from a tablet), asks Cloudflare's, then
     * Google's DNS over HTTPS, reached by IP address, so no local DNS is needed.
     */
    internal class RescueDns(private val first: Dns, private val rescuers: List<Pair<String, Dns>> = defaultRescuers) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return first.lookup(hostname)
            } catch (e: java.net.UnknownHostException) {
                // What each one answered goes into the message, so a report shows what blocks it.
                val why = mutableListOf("this device: ${e.message?.substringAfterLast(": ")?.take(70) ?: "no answer"}")
                for ((name, r) in rescuers) {
                    try {
                        val found = r.lookup(hostname)
                        if (found.isNotEmpty()) return found
                        why += "$name: no answer"
                    } catch (x: Exception) {
                        // DNS over HTTPS reports only the name; the reason (blocked, timeout, certificate…) is the cause.
                        val reason = generateSequence(x as Throwable) { it.cause }.last()
                        why += "$name: ${reason.javaClass.simpleName}${reason.message?.takeIf { it != hostname }?.let { " ${it.take(70)}" } ?: ""}"
                    }
                }
                throw java.net.UnknownHostException("$hostname not found (${why.joinToString("; ")})")
            }
        }

        companion object {
            private val defaultRescuers: List<Pair<String, Dns>> by lazy {
                val boot = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
                listOf(
                    Triple("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
                    Triple("Google DNS", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
                ).map { (name, url, ips) ->
                    name to DnsOverHttps.Builder().client(boot).url(url.toHttpUrl()).bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).build()
                }
            }
        }
    }

    private fun form(vararg pairs: Pair<String, String>) = FormBody.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()

    /** Step 1: asks Google for a code to show. */
    suspend fun startSignIn(): SignIn {
        if (!configured) throw CloudException("Google sign-in isn't set up in this build")
        val (code, body) = call(Request.Builder().url(endpoints.deviceCode).post(form("client_id" to clientId, "scope" to SCOPE)).build())
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw CloudException("Google didn't answer ($code)")
        if (code != 200) throw CloudException(o.optString("error_description").ifBlank { o.optString("error", "error $code") })
        return SignIn(
            o.getString("user_code"),
            o.optString("verification_url").ifBlank { o.optString("verification_uri", "https://www.google.com/device") },
            o.getString("device_code"),
            o.optInt("interval", 5).coerceAtLeast(1),
            System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000,
        )
    }

    /** Step 2: waits until the code has been entered (true), or it was refused or expired (exception). */
    suspend fun finishSignIn(signIn: SignIn): Boolean {
        var interval = signIn.intervalSeconds * 1000L
        while (System.currentTimeMillis() < signIn.expiresAt) {
            delay(interval)
            val (code, body) = call(Request.Builder().url(endpoints.token).post(form(
                "client_id" to clientId, "client_secret" to clientSecret, "device_code" to signIn.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )).build())
            val o = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (code == 200 && o.has("access_token")) {
                saveTokens(o)
                prefs.edit().putString("email", runCatching { fetchEmail() }.getOrNull()).apply()
                publish()
                return true
            }
            when (o.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5000
                "access_denied" -> throw CloudException("Sign-in was cancelled")
                "expired_token" -> throw CloudException("The code expired. Try again.")
                else -> throw CloudException(o.optString("error_description").ifBlank { o.optString("error", "error $code") })
            }
        }
        throw CloudException("The code expired. Try again.")
    }

    private fun saveTokens(o: JSONObject) {
        prefs.edit().apply {
            putString("access", o.getString("access_token"))
            putLong("access_until", System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000)
            o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { putString("refresh", it) }
        }.commit()
    }

    private val tokenLock = Mutex()

    /** A valid access token, refreshed when needed. */
    private suspend fun accessToken(force: Boolean = false): String = tokenLock.withLock {
        val current = prefs.getString("access", null)
        if (!force && current != null && System.currentTimeMillis() < prefs.getLong("access_until", 0)) return current
        val refresh = prefs.getString("refresh", null) ?: throw CloudException("Not signed in")
        val (code, body) = call(Request.Builder().url(endpoints.token).post(form(
            "client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to refresh, "grant_type" to "refresh_token",
        )).build())
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (code == 200 && o?.has("access_token") == true) { saveTokens(o); return o.getString("access_token") }
        if (o?.optString("error") == "invalid_grant") {
            // Access was removed (in the Google account, or the password changed): this device is signed out.
            forget()
            throw CloudException("Google access was removed. Sign in again.")
        }
        throw CloudException("Couldn't reach Google (${o?.optString("error")?.ifBlank { null } ?: code})")
    }

    /** Calls Drive with the token; on 401 refreshes once. */
    private suspend fun drive(build: Request.Builder): Pair<Int, String> {
        var (code, body) = call(build.header("Authorization", "Bearer ${accessToken()}").build())
        if (code == 401) {
            val again = call(build.header("Authorization", "Bearer ${accessToken(force = true)}").build())
            code = again.first; body = again.second
        }
        return code to body
    }

    private suspend fun fetchEmail(): String? {
        val (code, body) = drive(Request.Builder().url("${endpoints.drive}/about?fields=user(emailAddress,displayName)"))
        if (code != 200) return null
        val user = JSONObject(body).optJSONObject("user") ?: return null
        return user.optString("emailAddress").ifBlank { user.optString("displayName") }.ifBlank { null }
    }

    private fun forget() {
        prefs.edit().remove("access").remove("access_until").remove("refresh").remove("email").remove("last_backup").remove("last_hash").commit()
        publish()
    }

    /** Signs this device out and removes its access from the Google account. The backup stays in Drive. */
    suspend fun signOut() {
        val token = prefs.getString("refresh", null)
        forget()
        if (token != null) runCatching { call(Request.Builder().url(endpoints.revoke).post(form("token" to token)).build()) }
    }

    // ------------------------------------------------------------------ backup file

    /** A backup in Drive: which device saved it, when, with which app version. */
    data class Remote(val id: String, val modified: String, val device: String?, val app: String?, val size: Long, val deviceId: String?) {
        /** This device's own backup. */
        val mine: Boolean get() = deviceId != null && deviceId == CloudBackup.deviceId
        /** When it was saved, in milliseconds (0 if unknown). */
        val time: Long get() = runCatching { java.time.Instant.parse(modified).toEpochMilli() }.getOrDefault(0)
    }

    /** Every device's backup in this account, newest first. */
    suspend fun backups(): List<Remote> {
        val q = URLEncoder.encode("name contains '$FILE_PREFIX' and trashed = false", "UTF-8")
        val (code, body) = drive(Request.Builder().url(
            "${endpoints.drive}/files?q=$q&pageSize=50&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime,size,appProperties)"))
        if (code != 200) throw CloudException("Drive answered $code")
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            val props = f.optJSONObject("appProperties")
            Remote(f.getString("id"), f.optString("modifiedTime"), props?.optString("device")?.ifBlank { null }, props?.optString("app")?.ifBlank { null },
                f.optString("size").toLongOrNull() ?: 0, props?.optString("deviceId")?.ifBlank { null })
        }.sortedByDescending { it.time }
    }

    /** The "Potatoflix backups" folder: the one the app made before (on any device), or a new one. */
    private suspend fun folder(): String {
        val q = URLEncoder.encode("mimeType = 'application/vnd.google-apps.folder' and name = '$FOLDER' and trashed = false", "UTF-8")
        val (code, body) = drive(Request.Builder().url("${endpoints.drive}/files?q=$q&pageSize=10&orderBy=createdTime&fields=files(id)"))
        if (code != 200) throw CloudException("Drive answered $code")
        JSONObject(body).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }?.let { return it }
        val meta = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder")
        val (made, answer) = drive(Request.Builder().url("${endpoints.drive}/files?fields=id").post(meta.toString().toRequestBody(json)))
        if (made !in 200..299) throw CloudException("Drive answered $made: ${answer.take(200)}")
        return JSONObject(answer).getString("id")
    }

    /** The newest backup of any device. */
    suspend fun remote(): Remote? = backups().firstOrNull()

    /** Removes one device's backup (an old phone, a reset TV). */
    suspend fun delete(remote: Remote) {
        val (code, _) = drive(Request.Builder().url("${endpoints.drive}/files/${remote.id}").delete())
        if (code !in 200..299 && code != 404) throw CloudException("Drive answered $code")
    }

    private val backupLock = Mutex()

    /** Uploads the backup now (or only if it changed). Returns false when there was nothing new to upload. */
    suspend fun backupNow(onlyIfChanged: Boolean = false): Boolean = backupLock.withLock {
        val content = withContext(Dispatchers.IO) { Backup.export() }
        val hash = hashOf(content)
        if (onlyIfChanged && hash == prefs.getString("last_hash", null)) return false
        publish(busy = "Backing up…")
        try {
            val existing = backups().firstOrNull { it.deviceId == deviceId }
            val meta = JSONObject().put("name", "$FILE_PREFIX-$deviceId.json")
                .put("appProperties", JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("app", runCatching { App.context.packageManager.getPackageInfo(App.context.packageName, 0).versionName }.getOrNull() ?: "?").put("deviceId", deviceId))
            if (existing == null) meta.put("parents", org.json.JSONArray().put(folder()))
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody(json))
                .addPart(content.toRequestBody(json))
                .build()
            val request = if (existing == null) Request.Builder().url("${endpoints.upload}/files?uploadType=multipart&fields=id").post(body)
            else Request.Builder().url("${endpoints.upload}/files/${existing.id}?uploadType=multipart&fields=id").patch(body)
            val (code, answer) = drive(request)
            if (code !in 200..299) throw CloudException("Drive answered $code: ${answer.take(200)}")
            prefs.edit().putLong("last_backup", System.currentTimeMillis()).putString("last_hash", hash).apply()
            publish()
            true
        } catch (e: Exception) {
            publish(error = e.message ?: e.toString())
            throw e
        }
    }

    /**
     * Downloads a backup ([which], or the newest of any device) and restores it; then the missing extensions are
     * downloaded. Returns their names.
     */
    suspend fun restore(which: Remote? = null): List<String> = backupLock.withLock {
        publish(busy = "Restoring…")
        try {
            val r = which ?: remote() ?: throw CloudException("There's no backup in this Google account yet")
            val (code, content) = drive(Request.Builder().url("${endpoints.drive}/files/${r.id}?alt=media"))
            if (code != 200) throw CloudException("Drive answered $code")
            withContext(Dispatchers.IO) { Backup.import(content) }
            prefs.edit().putString("last_hash", hashOf(content)).apply()
            publish(busy = "Downloading the extensions…")
            val got = runCatching { PluginManager.installMissing(includeNew = false) }.getOrDefault(emptyList())
            publish()
            got
        } catch (e: Exception) {
            publish(error = e.message ?: e.toString())
            throw e
        }
    }

    /** The backup minus its time stamp, so an unchanged library doesn't upload again. */
    private fun hashOf(content: String): String {
        val stable = runCatching { JSONObject(content).apply { remove("created") }.toString() }.getOrDefault(content)
        return MessageDigest.getInstance("SHA-256").digest(stable.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ automatic backup

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    /** Starts the automatic backup (every 5 minutes, only when something changed). */
    fun startAuto(firstDelayMs: Long = 60_000) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            delay(firstDelayMs)
            while (isActive) {
                soonNow()
                delay(AUTO_INTERVAL_MS)
            }
        }
    }

    /** Backs up now if automatic backup is on and something changed (the app going to the background calls it). */
    fun soon() { scope.launch { soonNow() } }

    private suspend fun soonNow() {
        if (!configured || !auto || prefs.getString("refresh", null) == null) return
        runCatching { backupNow(onlyIfChanged = true) }
    }
}
