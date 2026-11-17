package org.wellshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * In-app updates, so students never download the app again.
 *
 * About twice a day WellShare reads update.json from the latest GitHub release of this project.
 * If it describes a newer version, WellShare downloads it into its private storage and checks:
 *   1. the SHA-256 checksum matches update.json,
 *   2. it is WellShare (same package name) with exactly the announced, higher version number,
 *   3. it is signed with the same key as the installed app (Android checks this again itself).
 * Then it installs it. On Android 12+ that needs no tap; otherwise, or whenever the phone asks,
 * the student gets one "Install" tap.
 */
object Updater {
    const val ACTION_STATUS = "org.wellshare.app.UPDATE_STATUS"

    private const val SP = "wellshare_update"
    private const val CHANNEL_READY = "update_ready"
    private const val CHANNEL_DONE = "update_done"
    private const val NOTIFY_ID = 2
    private val CHECK_EVERY_MS = TimeUnit.HOURS.toMillis(12)
    private val JITTER_MS = TimeUnit.HOURS.toMillis(3)
    private val RETRY_MS = TimeUnit.HOURS.toMillis(2)

    enum class State { IDLE, UP_TO_DATE, NOT_PUBLISHED, DOWNLOADING, INSTALLING, NEEDS_TAP, NEEDS_REINSTALL, FAILED }

    data class Status(
        val state: State,
        val checkedAt: Long,
        val latestName: String?,
        val message: String?,
        val updatedTo: String?,
        val updatedAt: Long,
    )

    private class UpdateError(message: String) : Exception(message)
    private class KeyChanged : Exception()

    /** Off for builds made without a GitHub repository (for example on your own computer). */
    val enabled: Boolean get() = UpdateMath.releasesBase(BuildConfig.UPDATE_URL) != null

    /** Where a student can download the app by hand, if an automatic update is impossible. */
    val releasesPage: String? get() = UpdateMath.releasesPage(BuildConfig.UPDATE_URL)

    /** The student has allowed WellShare to install its own updates. */
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun status(c: Context): Status {
        val s = sp(c)
        val state = runCatching { State.valueOf(s.getString("state", null) ?: "IDLE") }.getOrDefault(State.IDLE)
        return Status(state, s.getLong("checked_at", 0L), s.getString("latest_name", null),
            s.getString("message", null), s.getString("updated_to", null), s.getLong("updated_at", 0L))
    }

    private fun save(c: Context, state: State, message: String?) {
        sp(c).edit().putString("state", state.name).putString("message", message).apply()
    }

    private fun scheduleNext(c: Context, delayMs: Long) {
        sp(c).edit().putLong("next_check_at", System.currentTimeMillis() + delayMs + Random.nextLong(JITTER_MS)).apply()
    }

    /** Called by the hourly sync. Checks about twice a day (spread out, so a hostel's phones don't all check at once). */
    fun maybeCheck(c: Context) {
        if (!enabled) return
        if (System.currentTimeMillis() < sp(c).getLong("next_check_at", 0L)) return
        check(c)
    }

    /** Looks for a newer version and, if there is one, downloads, verifies and installs it. Background thread. */
    @Synchronized
    fun check(c: Context): Status {
        if (!enabled) return status(c)
        sp(c).edit().putLong("checked_at", System.currentTimeMillis()).apply()
        try {
            val info = fetchInfo()
            if (info == null) {
                save(c, State.NOT_PUBLISHED, null)
                scheduleNext(c, CHECK_EVERY_MS)
                return status(c)
            }
            if (!UpdateMath.isNewer(BuildConfig.VERSION_CODE.toLong(), info)) {
                clearFiles(c)
                save(c, State.UP_TO_DATE, null)
                scheduleNext(c, CHECK_EVERY_MS)
                return status(c)
            }
            sp(c).edit().putString("latest_name", info.versionName).apply()
            val mine = installedCerts(c)
            if (info.certSha256 != null && mine.isNotEmpty() && info.certSha256 !in mine) throw KeyChanged()

            val apk = download(c, info)
            verifyArchive(c, apk, info, mine)
            sp(c).edit()
                .putString("ready_path", apk.absolutePath)
                .putString("ready_sha", info.sha256)
                .putLong("ready_code", info.versionCode)
                .apply()
            save(c, State.INSTALLING, null)
            install(c, apk)
            scheduleNext(c, CHECK_EVERY_MS)
        } catch (_: KeyChanged) {
            clearFiles(c)
            save(c, State.NEEDS_REINSTALL, "The new version was signed with a different key, so it can't replace this one by itself. Install it once by hand from the download page.")
            scheduleNext(c, CHECK_EVERY_MS)
        } catch (e: UpdateError) {
            save(c, State.FAILED, e.message)
            scheduleNext(c, RETRY_MS)
        } catch (_: IOException) {
            save(c, State.FAILED, "Couldn't reach the update server.")
            scheduleNext(c, RETRY_MS)
        } catch (e: Exception) {
            save(c, State.FAILED, "Update failed (${e.javaClass.simpleName}).")
            scheduleNext(c, RETRY_MS)
        }
        return status(c)
    }

    /** Installs an update that was already downloaded and checked (the student tapped "Install"). */
    @Synchronized
    fun installReady(c: Context): Boolean {
        val s = sp(c)
        val f = File(s.getString("ready_path", null) ?: return false)
        val ok = f.exists() && f.parentFile == updatesDir(c) &&
            s.getLong("ready_code", 0L) > BuildConfig.VERSION_CODE &&
            fileSha256(f) == s.getString("ready_sha", "")
        if (!ok) { clearFiles(c); return false }
        save(c, State.INSTALLING, null)
        install(c, f)
        return true
    }

    // ---------- Fetching ----------

    /** Opens an HTTPS connection, following redirects only to allowed GitHub addresses. */
    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            if (!UpdateMath.allowedHost(url)) throw UpdateError("Update address not allowed.")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "WellShare/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            if (next.isNullOrBlank()) throw IOException("redirect without an address")
            url = URL(URL(url), next).toString()
        }
        throw IOException("too many redirects")
    }

    /** update.json of the latest release, or null if the latest release has none. */
    private fun fetchInfo(): UpdateMath.Info? {
        val conn = open(BuildConfig.UPDATE_URL)
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > UpdateMath.MAX_INFO_BYTES) throw UpdateError("The update file is too big.")
                }
            }
            val o = JSONObject(out.toString("UTF-8"))
            val info = UpdateMath.Info(
                versionCode = o.optLong("version_code", 0L),
                versionName = o.optString("version_name"),
                apkUrl = o.optString("apk"),
                sha256 = UpdateMath.normalizeHex(o.optString("sha256")) ?: "",
                size = o.optLong("size", 0L),
                certSha256 = if (o.isNull("cert_sha256")) null else UpdateMath.normalizeHex(o.optString("cert_sha256")),
                notes = o.optString("notes").take(600),
            )
            UpdateMath.problem(info, BuildConfig.UPDATE_URL)?.let { throw UpdateError("The update file is not valid: $it.") }
            return info
        } finally {
            conn.disconnect()
        }
    }

    private fun updatesDir(c: Context): File = File(c.cacheDir, "updates").apply { mkdirs() }

    private fun clearFiles(c: Context) {
        updatesDir(c).listFiles()?.forEach { it.delete() }
        sp(c).edit().remove("ready_path").remove("ready_sha").remove("ready_code").apply()
    }

    /** Downloads the new app into private storage, checking size and SHA-256 on the way. */
    private fun download(c: Context, info: UpdateMath.Info): File {
        val dir = updatesDir(c)
        val target = File(dir, "WellShare-${info.versionCode}.apk")
        if (target.exists() && fileSha256(target) == info.sha256) return target
        clearFiles(c)
        save(c, State.DOWNLOADING, null)
        val part = File(dir, "download.part")
        val conn = open(info.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > info.size) throw UpdateError("The downloaded file is bigger than announced, so it was deleted.")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total != info.size) throw IOException("download incomplete")
            if (UpdateMath.hex(md.digest()) != info.sha256) {
                throw UpdateError("The downloaded file failed its checksum check, so it was deleted.")
            }
            if (!part.renameTo(target)) throw IOException("couldn't save the update")
            return target
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun fileSha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return UpdateMath.hex(md.digest())
    }

    // ---------- Checking the downloaded app ----------

    @Suppress("DEPRECATION")
    private fun certFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certsOf(p: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo
            when {
                si == null -> null
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            p.signatures
        }
        return sigs?.map { UpdateMath.sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun installedCerts(c: Context): Set<String> = try {
        certsOf(c.packageManager.getPackageInfo(c.packageName, certFlags()))
    } catch (_: Exception) {
        emptySet()
    }

    @Suppress("DEPRECATION")
    private fun versionOf(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    private fun verifyArchive(c: Context, apk: File, info: UpdateMath.Info, mine: Set<String>) {
        val a = c.packageManager.getPackageArchiveInfo(apk.absolutePath, certFlags())
        val problem = when {
            a == null -> "The downloaded file is not a valid app."
            a.packageName != c.packageName -> "The downloaded file is a different app."
            versionOf(a) != info.versionCode -> "The downloaded file has the wrong version number."
            else -> null
        }
        if (problem != null) {
            clearFiles(c)
            throw UpdateError(problem)
        }
        if (!UpdateMath.sameSigner(mine, certsOf(a!!))) throw KeyChanged()
    }

    // ---------- Installing ----------

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            // An app updating itself may skip the confirmation tap (Android 12+).
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("WellShare.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Android writes the result into this intent, so it has to be mutable. It is explicit:
                // only WellShare's own, non-exported receiver can get it.
                val result = PendingIntent.getBroadcast(
                    c, 42, Intent(c, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                )
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            try { installer.abandonSession(id) } catch (_: Exception) {}
            save(c, State.FAILED, "Couldn't start the update.")
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)

    /** Result of an install, sent by Android to UpdateReceiver. */
    fun onStatus(c: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The phone wants a tap. If WellShare is open, show Android's install screen now;
                // otherwise leave a notification.
                val confirm = confirmIntent(intent)
                if (confirm != null && MainActivity.visible) {
                    try {
                        c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        save(c, State.INSTALLING, null)
                        return
                    } catch (_: Exception) {}
                }
                save(c, State.NEEDS_TAP, null)
                notifyReady(c)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                clearFiles(c)
                save(c, State.UP_TO_DATE, null)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> save(c, State.NEEDS_TAP, null) // the install screen was closed
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                clearFiles(c)
                save(c, State.NEEDS_REINSTALL, "This version can't replace the installed one. Install it once by hand from the download page.")
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> save(c, State.FAILED, "Not enough free space on the phone for the update.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> save(c, State.FAILED, "The phone blocked the update. Open WellShare and tap \"Check for updates\" to try again.")
            else -> {
                clearFiles(c)
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(120)
                save(c, State.FAILED, "Update failed" + (if (why.isNullOrBlank()) "." else ": $why"))
            }
        }
    }

    /** Runs in the new version right after it was installed. */
    fun onUpdated(c: Context) {
        clearFiles(c)
        val now = System.currentTimeMillis()
        sp(c).edit()
            .putString("state", State.UP_TO_DATE.name).remove("message")
            .putString("updated_to", BuildConfig.VERSION_NAME).putLong("updated_at", now)
            .apply()
        if (!Prefs(c).isRegistered) return
        Sync.schedule(c)
        Notify.showSharing(c)
        notify(c, CHANNEL_DONE, NotificationManager.IMPORTANCE_LOW, "Updates installed",
            "WellShare updated to version ${BuildConfig.VERSION_NAME}",
            "Installed automatically. Your registration and history are kept.", install = false)
    }

    // ---------- Notifications ----------

    private fun notifyReady(c: Context) {
        val name = status(c).latestName ?: "new"
        notify(c, CHANNEL_READY, NotificationManager.IMPORTANCE_DEFAULT, "Updates ready to install",
            "WellShare update ready", "Tap to install version $name. It takes a few seconds and keeps your data.", install = true)
    }

    private fun notify(c: Context, channel: String, importance: Int, channelName: String, title: String, text: String, install: Boolean) {
        if (!Notify.allowed(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(channel, channelName, importance).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(
            c, if (install) 3 else 4,
            Intent(c, MainActivity::class.java).setAction(if (install) MainActivity.ACTION_INSTALL_UPDATE else Intent.ACTION_MAIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIFY_ID, n) } catch (_: SecurityException) {}
    }

    fun cancelNotification(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID)
    }
}

/** Gets install results from Android, and runs once in each new version after an update. Not exported. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Updater.ACTION_STATUS -> Updater.onStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.onUpdated(context)
        }
    }
}
