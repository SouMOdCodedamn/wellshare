package org.wellshare.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Runs while the student has turned WellShare on in Accessibility settings. It does two things only:
 *  1. Notes which website (site name only) is open in Chrome-family browsers, and for how long.
 *  2. Closes an app when the daily timer the mentor set for it has run out.
 * It never reads messages, passwords, or what the student types.
 */
class Watcher : AccessibilityService() {

    companion object {
        /** Browser package -> id of its address bar. */
        val BROWSERS = mapOf(
            "com.android.chrome" to "url_bar",
            "com.chrome.beta" to "url_bar",
            "com.chrome.dev" to "url_bar",
            "com.chrome.canary" to "url_bar",
            "com.brave.browser" to "url_bar",
            "com.microsoft.emmx" to "url_bar",
            "com.sec.android.app.sbrowser" to "location_bar_edit_text",
            "com.vivaldi.browser" to "url_bar",
            "com.kiwibrowser.browser" to "url_bar",
            "org.mozilla.firefox" to "mozac_browser_toolbar_url_view",
            "com.opera.browser" to "url_field",
            "com.duckduckgo.mobile.android" to "omnibarTextInput",
        )
        /** Sync when the phone is in use and the last upload is older than this. */
        private const val SYNC_EVERY_MS = 50 * 60 * 1000L
        /** After a failed attempt (no internet), wait this long before trying again. */
        private const val RETRY_MS = 10 * 60 * 1000L
        private const val MAX_SEGMENT_MS = 3 * 60 * 60 * 1000L

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, Watcher::class.java)
            return enabled.split(':').any {
                val c = ComponentName.unflattenFromString(it.trim())
                c != null && c.packageName == me.packageName && c.className == me.className
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var sites: SitesStore
    private var safe: Set<String> = emptySet()
    private var imes: Set<String> = emptySet()
    private val activityCache = HashMap<String, Boolean>()

    @Volatile private var fgPkg: String? = null
    private var siteDomain: String? = null
    private var siteStart = 0L
    private var lastBrowserRead = 0L
    @Volatile private var checkToken = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    closeSite(System.currentTimeMillis())
                    fgPkg = null
                    checkToken++
                }
                // The phone is in use (and has network): a good moment to upload.
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> maybeSync()
            }
        }
    }

    /** Checks every 15 minutes while the service is alive (uploads at most hourly), independent of Android's job scheduler. */
    private val heartbeat = object : Runnable {
        override fun run() {
            maybeSync()
            main.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    /**
     * Uploads from here whenever the phone is being used and the last upload is old.
     * Android can hold back background jobs for a day or more for apps that are rarely opened;
     * this keeps reports flowing without the student opening WellShare.
     */
    private fun maybeSync() {
        val prefs = Prefs(this)
        if (!prefs.isRegistered) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastSyncAt < SYNC_EVERY_MS || now - prefs.lastSyncAttemptAt < RETRY_MS) return
        prefs.lastSyncAttemptAt = now
        closeSite(now)
        worker.post { try { Sync.run(applicationContext) } catch (_: Exception) {} }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        workerThread = HandlerThread("wellshare-watcher").also { it.start() }
        worker = Handler(workerThread.looper)
        sites = SitesStore(this)
        safe = SafeApps.of(this)
        imes = try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenReceiver, f)
        Notify.showSharing(this)
        main.postDelayed(heartbeat, 60 * 1000L)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == "com.android.systemui" || pkg in imes) return
                if (!isActivity(pkg, e.className?.toString())) return
                onForeground(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == fgPkg && pkg in BROWSERS) readBrowser(pkg, throttle = true)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(heartbeat)
        closeSite(System.currentTimeMillis())
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    // ---------- foreground app ----------

    private fun onForeground(pkg: String) {
        if (pkg == fgPkg) return
        maybeSync()
        val prev = fgPkg
        fgPkg = pkg
        if (prev != null && prev in BROWSERS) closeSite(System.currentTimeMillis())
        checkToken++
        scheduleLimitCheck(pkg, 0L)
        if (pkg in BROWSERS) readBrowser(pkg, throttle = false)
    }

    /** True if the window belongs to a real screen of the app (not a popup or toast). */
    private fun isActivity(pkg: String, cls: String?): Boolean {
        if (cls.isNullOrBlank()) return false
        val key = "$pkg/$cls"
        return activityCache.getOrPut(key) {
            try {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    // ---------- app timers ----------

    private fun scheduleLimitCheck(pkg: String, delayMs: Long) {
        val token = checkToken
        worker.postDelayed({ checkLimit(pkg, token) }, delayMs)
    }

    /** Runs on the worker thread. */
    private fun checkLimit(pkg: String, token: Long) {
        if (token != checkToken || pkg != fgPkg || pkg in safe) return
        val limit = Prefs(this).limits.firstOrNull { it.pkg == pkg } ?: return
        val used = try { UsageCollector.todaySeconds(this, pkg) } catch (_: Exception) { return }
        val allowed = limit.minutes * 60L
        if (used >= allowed) {
            main.post { if (token == checkToken && fgPkg == pkg) block(limit) }
        } else {
            // check again right when the time runs out (at most every 30 min, in case usage data lags)
            val wait = ((allowed - used) * 1000L + 1500L).coerceAtMost(30 * 60 * 1000L)
            scheduleLimitCheck(pkg, wait)
        }
    }

    private fun block(limit: AppLimit) {
        checkToken++
        val i = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(BlockActivity.EXTRA_NAME, limit.name)
            .putExtra(BlockActivity.EXTRA_MINUTES, limit.minutes)
        try {
            startActivity(i)
        } catch (_: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ---------- websites ----------

    private fun readBrowser(pkg: String, throttle: Boolean) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastBrowserRead < 1000) return
        lastBrowserRead = now
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (root.packageName?.toString() != pkg) return
        val node = try {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/${BROWSERS[pkg]}").firstOrNull()
        } catch (_: Exception) { null } ?: return
        // While the student is typing in the address bar, read nothing.
        if (node.isFocused) return
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) return
        val domain = SiteMath.domainOf(node.text?.toString()) ?: return

        if (domain != siteDomain) {
            closeSite(now)
            siteDomain = domain
            siteStart = now
        } else if (now - siteStart > 60_000) {
            // save progress every minute so syncs include the site that is open right now
            sites.add(siteStart, now, domain)
            siteStart = now
        }
    }

    private fun closeSite(now: Long) {
        val d = siteDomain ?: return
        val end = minOf(now, siteStart + MAX_SEGMENT_MS)
        sites.add(siteStart, end, d)
        siteDomain = null
    }
}

/** Apps that are never blocked: calls, messages, settings, home screen, WellShare itself. */
object SafeApps {
    fun of(context: Context): Set<String> {
        val set = hashSetOf(
            context.packageName, "android", "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.google.android.dialer", "com.android.dialer",
            "com.samsung.android.dialer", "com.android.contacts", "com.google.android.contacts",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        )
        for (i in intents) {
            try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(i, 0).forEach { set.add(it.activityInfo.packageName) }
            } catch (_: Exception) {}
        }
        return set
    }
}
