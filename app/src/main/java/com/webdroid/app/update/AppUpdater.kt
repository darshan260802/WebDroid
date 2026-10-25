package com.webdroid.app.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.webdroid.app.BuildConfig
import com.webdroid.app.MainActivity
import com.webdroid.app.R
import com.webdroid.app.data.AppSettings
import com.webdroid.app.util.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CacheControl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What CI publishes next to every release APK as `update.json`. */
@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long = 0,
    val tag: String = "v$versionName",
    val apkUrl: String,
    val size: Long = 0,
    val sha256: String? = null,
    val minSdk: Int = 26,
    val notes: String = "",
    val publishedAt: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    /** Newer release found but not downloaded yet (metered network, or a failed download). */
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Over-the-air updates from this repository's GitHub releases. Every release
 * CI publishes carries an `update.json`; WebDroid checks it at launch and once
 * a day, downloads the APK in the background (Wi-Fi only unless allowed),
 * verifies its checksum and signature, and installs it with one tap through
 * Android's package installer.
 */
object AppUpdater {
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 7301
    private const val PREFS = "updater"
    const val EXTRA_INSTALL_UPDATE = "install_update"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var restored = false
    /** Install requested while "Install unknown apps" was still off. */
    @Volatile private var installAfterPermission = false

    private val latestJsonUrl get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/update.json"
    private val latestApiUrl get() = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    val releasesPage get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases"

    private fun dir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }
    private fun pendingFile(context: Context) = File(dir(context), "pending.json")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckedAt(context: Context): Long = prefs(context).getLong("checked_at", 0L)

    /** Restores a downloaded-but-not-installed update after a process restart. */
    fun restore(context: Context) {
        if (restored) return
        restored = true
        val pending = pendingFile(context)
        val info = runCatching { json.decodeFromString(UpdateInfo.serializer(), pending.readText()) }.getOrNull()
        val apk = info?.let { apkFile(context, it) }
        if (info != null && apk != null && apk.exists() && isNewer(info)) {
            _state.value = UpdateState.Ready(info, apk)
        } else {
            clearDownloads(context)
        }
    }

    /** Launch-time check, at most once an hour. */
    fun checkIfDue(context: Context) {
        restore(context)
        if (!AppSettings.get(context).state.value.autoUpdate) return
        if (System.currentTimeMillis() - lastCheckedAt(context) < TimeUnit.HOURS.toMillis(1)) return
        scope.launch { check(context, userInitiated = false) }
    }

    fun checkNow(context: Context) {
        restore(context)
        scope.launch { check(context, userInitiated = true) }
    }

    fun download(context: Context) {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info ?: return
            else -> return
        }
        scope.launch { mutex.withLock { downloadLocked(context, info, notify = false) } }
    }

    /** Hide the update card for this version for a day. */
    fun snooze(context: Context) {
        val info = currentInfo() ?: return
        prefs(context).edit { putString("snoozed", "${info.versionCode}:${info.versionName}:${System.currentTimeMillis()}") }
        _snoozeTick.value++
    }

    private val _snoozeTick = MutableStateFlow(0)
    val snoozeTick: StateFlow<Int> = _snoozeTick.asStateFlow()

    fun isSnoozed(context: Context, info: UpdateInfo): Boolean {
        val raw = prefs(context).getString("snoozed", null) ?: return false
        val parts = raw.split(':')
        if (parts.size != 3) return false
        val sameVersion = parts[0] == info.versionCode.toString() && parts[1] == info.versionName
        val at = parts[2].toLongOrNull() ?: return false
        return sameVersion && System.currentTimeMillis() - at < TimeUnit.DAYS.toMillis(1)
    }

    private fun currentInfo(): UpdateInfo? = when (val s = _state.value) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Ready -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }

    /* ------------------------------------------------------------------ */
    /* Checking                                                            */
    /* ------------------------------------------------------------------ */

    /** Runs a check (and a background download when allowed). Returns the new state. */
    suspend fun check(context: Context, userInitiated: Boolean, fromBackground: Boolean = false): UpdateState = mutex.withLock {
        restore(context)
        when (val s = _state.value) {
            is UpdateState.Downloading, is UpdateState.Installing -> return@withLock s
            else -> Unit
        }
        val previous = _state.value
        if (userInitiated || previous !is UpdateState.Ready) _state.value = UpdateState.Checking
        val info = try {
            withContext(Dispatchers.IO) { fetchLatest() }
        } catch (e: Exception) {
            _state.value = if (previous is UpdateState.Ready) previous
            else if (userInitiated) UpdateState.Failed("Couldn't reach GitHub. Check your connection.")
            else previous.takeUnless { it is UpdateState.Checking } ?: UpdateState.Idle
            return@withLock _state.value
        }
        prefs(context).edit { putLong("checked_at", System.currentTimeMillis()) }

        if (info == null || !isNewer(info) || Build.VERSION.SDK_INT < info.minSdk) {
            clearDownloads(context)
            _state.value = UpdateState.UpToDate(System.currentTimeMillis())
            return@withLock _state.value
        }
        if (previous is UpdateState.Ready && previous.info.versionCode == info.versionCode && previous.info.versionName == info.versionName && previous.file.exists()) {
            _state.value = previous
            return@withLock previous
        }
        val settings = AppSettings.get(context).state.value
        // On mobile data the 25 MB download waits for Wi-Fi or a tap, unless allowed.
        val mayDownload = (userInitiated || settings.autoUpdate) && (settings.updateOnMobileData || !isMetered(context))
        if (mayDownload) {
            downloadLocked(context, info, notify = fromBackground)
        } else {
            _state.value = UpdateState.Available(info)
            if (fromBackground) notifyAvailable(context, info, ready = false)
            _state.value
        }
    }

    private fun isNewer(info: UpdateInfo): Boolean =
        if (info.versionCode > 0) info.versionCode > BuildConfig.VERSION_CODE
        else compareVersions(info.versionName, BuildConfig.VERSION_NAME) > 0

    /** Compares dotted versions numerically: "0.10.0" > "0.9.2". */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun fetchLatest(): UpdateInfo? {
        val request = Request.Builder()
            .url(latestJsonUrl)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/json")
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw IOException("Empty update.json")
                return json.decodeFromString(UpdateInfo.serializer(), body)
            }
            if (response.code != 404) throw IOException("HTTP ${response.code}")
        }
        // Releases made before update.json existed: read the release itself.
        val api = Request.Builder()
            .url(latestApiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        Net.client.newCall(api).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val release = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val tag = release["tag_name"]?.jsonPrimitive?.content ?: return null
            val asset = release["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it.string("name")?.endsWith(".apk") == true } ?: return null
            return UpdateInfo(
                versionName = tag.removePrefix("v"),
                tag = tag,
                apkUrl = asset.string("browser_download_url") ?: return null,
                size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = asset.string("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                notes = release.string("body").orEmpty(),
                publishedAt = release.string("published_at"),
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun isMetered(context: Context): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    /* ------------------------------------------------------------------ */
    /* Downloading                                                         */
    /* ------------------------------------------------------------------ */

    private fun apkFile(context: Context, info: UpdateInfo) =
        File(dir(context), "WebDroid-${info.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")

    private suspend fun downloadLocked(context: Context, info: UpdateInfo, notify: Boolean): UpdateState = withContext(Dispatchers.IO) {
        val target = apkFile(context, info)
        val part = File(target.path + ".part")
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            dir(context).listFiles()?.filter { it != target && it != part && it.name.endsWith(".apk") }?.forEach { it.delete() }
            val client = Net.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(info.apkUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
                val body = response.body ?: throw IOException("Empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                var read = 0L
                var lastEmit = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastEmit > 120) {
                                lastEmit = now
                                _state.value = UpdateState.Downloading(info, (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !info.sha256.equals(actual, ignoreCase = true)) {
                throw IOException("The download was corrupted. Try again.")
            }
            verifyArchive(context, part)
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't save the update")
            pendingFile(context).writeText(json.encodeToString(UpdateInfo.serializer(), info))
            _state.value = UpdateState.Ready(info, target)
            if (notify) notifyAvailable(context, info, ready = true)
        } catch (e: Exception) {
            part.delete()
            _state.value = UpdateState.Failed(e.message ?: "Download failed", info)
        }
        _state.value
    }

    /** The APK must be WebDroid, newer, and signed with the same key. */
    private fun verifyArchive(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("The download isn't a valid app")
        if (archive.packageName != context.packageName) throw IOException("The download isn't WebDroid")
        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        if (archiveCode <= BuildConfig.VERSION_CODE) throw IOException("The download isn't newer than this version")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val expected = signatures(installed)
        val actual = signatures(archive)
        // Android refuses mismatched signatures anyway; this just fails earlier and clearer.
        if (actual.isNotEmpty() && expected.isNotEmpty() && actual.intersect(expected).isEmpty()) {
            throw IOException("The update isn't signed by WebDroid's release key")
        }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val sigs: Array<android.content.pm.Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        return (sigs ?: return emptySet()).map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun clearDownloads(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /* ------------------------------------------------------------------ */
    /* Installing                                                          */
    /* ------------------------------------------------------------------ */

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Hands the downloaded APK to Android's installer, which asks the user to confirm. */
    fun install(activity: Activity) {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!canInstall(activity)) {
            installAfterPermission = true
            android.widget.Toast.makeText(activity, "Allow WebDroid to install updates, then come back", android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            return
        }
        installAfterPermission = false
        NotificationManagerCompat.from(activity).cancel(NOTIFICATION_ID)
        _state.value = UpdateState.Installing(ready.info)
        val context = activity.applicationContext
        scope.launch {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(ready.file.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                        ready.file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                    val callback = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_INSTALL_STATUS)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Couldn't start the installer", ready.info)
            }
        }
    }

    /** Called from MainActivity.onResume: finish an install the permission screen interrupted. */
    fun onResume(activity: Activity) {
        if (installAfterPermission && canInstall(activity) && _state.value is UpdateState.Ready) install(activity)
    }

    internal fun onInstallResult(context: Context, status: Int, message: String?) {
        val info = currentInfo()
        val file = info?.let { apkFile(context, it) }
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // The process is replaced right after.
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _state.value = if (info != null && file?.exists() == true) UpdateState.Ready(info, file) else UpdateState.Idle
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update is signed differently from the installed app. Install it from GitHub instead."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installing was blocked on this device"
                    else -> message ?: "The update couldn't be installed"
                }
                _state.value = UpdateState.Failed(reason, info)
            }
        }
    }

    /** First start after an update: tidy up and say so. */
    internal fun onUpdated(context: Context) {
        clearDownloads(context)
        prefs(context).edit { remove("snoozed") }
        _state.value = UpdateState.Idle
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_webdroid)
            .setContentTitle("WebDroid updated to ${BuildConfig.VERSION_NAME}")
            .setContentText("Tap to open")
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /* ------------------------------------------------------------------ */
    /* Notifications                                                       */
    /* ------------------------------------------------------------------ */

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New versions of WebDroid"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notifyAvailable(context: Context, info: UpdateInfo, ready: Boolean) {
        val shown = prefs(context).getString("notified", null)
        val key = "${info.versionCode}:${info.versionName}:$ready"
        if (shown == key) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_INSTALL_UPDATE, ready)
        val open = PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_webdroid)
            .setContentTitle(if (ready) "WebDroid ${info.versionName} is ready to install" else "WebDroid ${info.versionName} is available")
            .setContentText(firstNoteLine(info.notes) ?: if (ready) "Tap to install" else "Tap to download")
            .setAutoCancel(true)
            .setContentIntent(open)
        if (ready) builder.addAction(0, "Install", open)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            prefs(context).edit { putString("notified", key) }
        } catch (_: SecurityException) {
            // Notifications not allowed; the Home screen card still shows it.
        }
    }

    fun firstNoteLine(notes: String): String? = notes.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.removePrefix("- ")?.removePrefix("* ")
        ?.replace("**", "")?.replace("`", "")
}
