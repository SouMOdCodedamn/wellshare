package org.wellshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val ACTION_SOS = "org.wellshare.app.SOS"
        private const val REQ_SAFETY = 7
        private const val REQ_NOTIFY = 9
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    /** SOS was asked for from the notification. */
    private var sosRequested = false
    /** The SOS screen is showing; keep it until the student closes it. */
    @Volatile private var sosActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        if (intent?.action == ACTION_SOS) sosRequested = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_SOS) sosRequested = true
    }

    override fun onResume() {
        super.onResume()
        if (!sosActive) render()
    }

    /** Picks the right screen for where the student is in setup. */
    private fun render() {
        if (!prefs.isRegistered) {
            when {
                !prefs.consented -> showConsent()
                !UsageCollector.hasUsageAccess(this) -> showUsageAccess()
                !Watcher.isEnabled(this) -> showAccessibility()
                else -> showRegister()
            }
            return
        }
        if (!prefs.safetyAsked) { showSafety(); return }
        showHome(autoSync = System.currentTimeMillis() - prefs.lastSyncAt > 15 * 60 * 1000L)
        if (sosRequested) {
            sosRequested = false
            confirmSos()
        }
    }

    private fun setScreen(content: LinearLayout) {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.bg)
            isFillViewport = true
            addView(content)
        }
        // Keep content clear of the status bar / navigation bar (Android 15 draws edge-to-edge).
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun bullets(card: LinearLayout, items: List<String>) = items.forEach { card.addView(ui.body("•  $it")) }

    // ---------- 1. Consent ----------

    private fun showConsent() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 1 OF 5"))
        col.addView(ui.gap(6))
        col.addView(ui.title("Share your phone use with your mentor"))
        col.addView(ui.gap(12))
        col.addView(ui.body("WellShare sends a summary of your phone use to your mentor's dashboard every hour, so you can talk about it together. You see the same numbers in this app."))
        col.addView(ui.gap(16))

        val shared = ui.card()
        shared.addView(ui.heading("What your mentor sees"))
        shared.addView(ui.gap(8))
        bullets(shared, listOf(
            "Screen time, unlocks and use after midnight, per day",
            "Time spent in each of your top 20 apps",
            "Your top websites per day in Chrome and similar browsers: site names only, like youtube.com",
            "Whether WellShare's permissions are switched on",
            "Your name, roll number and phone model",
            "Your location, only when you press SOS",
        ))
        col.addView(shared, ui.fullWidth())
        col.addView(ui.gap(12))

        val can = ui.card()
        can.addView(ui.heading("What your mentor can do"))
        can.addView(ui.gap(8))
        bullets(can, listOf(
            "Set daily time limits on apps. When a limit runs out, WellShare closes that app until midnight.",
            "Calls, messages, Settings and WellShare are never blocked.",
        ))
        col.addView(can, ui.fullWidth())
        col.addView(ui.gap(12))

        val notShared = ui.card()
        notShared.addView(ui.heading("Never collected"))
        notShared.addView(ui.gap(8))
        bullets(notShared, listOf(
            "Messages, photos, contacts or call logs",
            "Full web addresses, searches, or what you type, watch or read",
            "Passwords",
            "Your location at any other time",
        ))
        col.addView(notShared, ui.fullWidth())
        col.addView(ui.gap(16))

        val agree = CheckBox(this).apply {
            text = "I agree to share this with my mentor. I can stop at any time; my mentor will see that I stopped."
            setTextColor(ui.ink)
            textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(ui.accent)
        }
        col.addView(agree)
        col.addView(ui.gap(16))
        col.addView(ui.button("Continue") {
            if (!agree.isChecked) {
                toast("Please tick the box to agree first.")
            } else {
                prefs.consented = true
                render()
            }
        }, ui.fullWidth())
        setScreen(col)
    }

    // ---------- 2. Usage access ----------

    private fun showUsageAccess() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 2 OF 5"))
        col.addView(ui.gap(6))
        col.addView(ui.title("Allow usage access"))
        col.addView(ui.gap(12))
        col.addView(ui.body("Android keeps the screen-time record that Digital Wellbeing shows. WellShare needs permission to read it."))
        col.addView(ui.gap(16))
        val steps = ui.card()
        steps.addView(ui.body("1.  Tap the button below"))
        steps.addView(ui.body("2.  Find and tap WellShare in the list"))
        steps.addView(ui.body("3.  Turn on \"Permit usage access\""))
        steps.addView(ui.body("4.  Press back to return here"))
        col.addView(steps, ui.fullWidth())
        col.addView(ui.gap(20))
        col.addView(ui.button("Open usage access settings") { openUsageSettings() }, ui.fullWidth())
        col.addView(ui.gap(20))
        col.addView(restrictedHelp(), ui.fullWidth())
        setScreen(col)
    }

    private fun openUsageSettings() {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
        val generic = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        try {
            startActivity(direct)
        } catch (_: Exception) {
            try { startActivity(generic) } catch (_: Exception) { toast("Open Settings > Apps > Special access > Usage access") }
        }
    }

    /** Android 13+ blocks some switches for apps installed from a downloaded APK until the user allows it. */
    private fun restrictedHelp(): LinearLayout {
        val c = ui.card()
        c.addView(ui.heading("Switch greyed out?"))
        c.addView(ui.gap(6))
        c.addView(ui.body("If you see \"Restricted setting\", Android is being careful because you installed WellShare from a download. To allow it:"))
        c.addView(ui.gap(6))
        c.addView(ui.body("1.  Tap \"Open app info\" below"))
        c.addView(ui.body("2.  Tap ⋮ (top-right corner)"))
        c.addView(ui.body("3.  Tap \"Allow restricted settings\" and confirm"))
        c.addView(ui.body("4.  Come back and try again"))
        c.addView(ui.gap(12))
        c.addView(ui.button("Open app info", primary = false) { openAppInfo() }, ui.fullWidth())
        return c
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            toast("Open Settings > Apps > WellShare")
        }
    }

    // ---------- 3. Accessibility (app timers + websites) ----------

    private fun showAccessibility() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 3 OF 5"))
        col.addView(ui.gap(6))
        col.addView(ui.title("Turn on app timers and website tracking"))
        col.addView(ui.gap(12))
        col.addView(ui.body("Your mentor can set daily limits on apps and sees your top websites. Android's Accessibility setting lets WellShare do this."))
        col.addView(ui.gap(16))
        val what = ui.card()
        what.addView(ui.heading("What WellShare does with it"))
        what.addView(ui.gap(8))
        bullets(what, listOf(
            "Reads the site name in the browser's address bar, like youtube.com. Never the full address, your searches or the page.",
            "Closes an app when your mentor's daily limit for it runs out.",
            "Nothing else. It doesn't read messages, passwords or what you type.",
        ))
        col.addView(what, ui.fullWidth())
        col.addView(ui.gap(12))
        val steps = ui.card()
        steps.addView(ui.body("1.  Tap the button below"))
        steps.addView(ui.body("2.  Tap \"Installed apps\" or \"Downloaded apps\" (some phones skip this)"))
        steps.addView(ui.body("3.  Tap WellShare and turn on \"Use WellShare\", then Allow"))
        steps.addView(ui.body("4.  Press back to return here"))
        col.addView(steps, ui.fullWidth())
        col.addView(ui.gap(20))
        col.addView(ui.button("Open Accessibility settings") { openAccessibilitySettings() }, ui.fullWidth())
        col.addView(ui.gap(20))
        col.addView(restrictedHelp(), ui.fullWidth())
        setScreen(col)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            toast("Open Settings > Accessibility > WellShare")
        }
    }

    // ---------- 4. Register ----------

    private fun showRegister() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 4 OF 5"))
        col.addView(ui.gap(6))
        col.addView(ui.title("Join your mentor's group"))
        col.addView(ui.gap(12))
        col.addView(ui.body("This phone will be linked to you only. One phone can belong to one participant, even if it has several user profiles."))
        prefs.lastError?.let {
            col.addView(ui.gap(12))
            col.addView(ui.small(it, ui.warn))
        }
        col.addView(ui.gap(20))

        val code = ui.input("Group code (from your mentor)", caps = true)
        val name = ui.input("Full name")
        val roll = ui.input("Roll number", caps = true)
        col.addView(code, ui.fullWidth()); col.addView(ui.gap(12))
        col.addView(name, ui.fullWidth()); col.addView(ui.gap(12))
        col.addView(roll, ui.fullWidth()); col.addView(ui.gap(12))

        val status = ui.small("")
        col.addView(status)
        col.addView(ui.gap(12))
        val spinner = ProgressBar(this).apply { visibility = View.GONE }
        col.addView(spinner)

        lateinit var submit: View
        submit = ui.button("Register this phone") {
            val c = code.text.toString().trim()
            val n = name.text.toString().trim()
            val r = roll.text.toString().trim()
            if (c.isEmpty() || n.length < 2 || r.isEmpty()) {
                status.setTextColor(ui.error); status.text = "Please fill in all three fields."
                return@button
            }
            submit.isEnabled = false
            spinner.visibility = View.VISIBLE
            status.setTextColor(ui.muted); status.text = "Registering…"
            Thread {
                try {
                    val device = DeviceId.get(this)
                    val reg = Api.register(c, n, r, device, "${Build.MANUFACTURER} ${Build.MODEL}",
                        Build.VERSION.RELEASE ?: "", BuildConfig.VERSION_NAME)
                    prefs.token = reg.token
                    prefs.deviceHash = device.hash
                    prefs.fullName = reg.fullName
                    prefs.rollNo = reg.rollNo
                    prefs.groupName = reg.groupName
                    prefs.sosPhone = reg.sosPhone
                    prefs.limits = reg.limits
                    prefs.lastError = null
                    Sync.schedule(this)
                    main.post {
                        if (reg.relinked) toast("Welcome back, ${reg.fullName}. This phone is linked to you again.")
                        render()
                    }
                } catch (e: Api.ApiError) {
                    main.post {
                        submit.isEnabled = true; spinner.visibility = View.GONE
                        status.setTextColor(ui.error); status.text = e.message
                    }
                } catch (e: Exception) {
                    main.post {
                        submit.isEnabled = true; spinner.visibility = View.GONE
                        status.setTextColor(ui.error); status.text = "Couldn't register: ${e.javaClass.simpleName}"
                    }
                }
            }.start()
        }
        col.addView(submit, ui.fullWidth())
        setScreen(col)
    }

    // ---------- 5. SOS + notifications ----------

    private fun safetyPermissions(): Array<String> = buildList {
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun showSafety() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 5 OF 5"))
        col.addView(ui.gap(6))
        col.addView(ui.title("Set up SOS"))
        col.addView(ui.gap(12))
        col.addView(ui.body("In an emergency, the SOS button calls your mentor straight away and sends them an alert with your location."))
        col.addView(ui.gap(16))
        val card = ui.card()
        card.addView(ui.heading("WellShare will ask to"))
        card.addView(ui.gap(8))
        bullets(card, listOf(
            "Make phone calls: so SOS can call your mentor without extra taps",
            "Use your location: only at the moment you press SOS",
            "Show notifications: a small notice that sharing is on, with an SOS button",
        ))
        col.addView(card, ui.fullWidth())
        col.addView(ui.gap(20))
        col.addView(ui.button("Allow") { requestPermissions(safetyPermissions(), REQ_SAFETY) }, ui.fullWidth())
        col.addView(ui.gap(12))
        col.addView(ui.button("Skip for now", primary = false) {
            prefs.safetyAsked = true
            render()
        }, ui.fullWidth())
        setScreen(col)
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SAFETY) prefs.safetyAsked = true
        Notify.showSharing(this)
        if (!sosActive) render()
    }

    // ---------- Home: the student's own report ----------

    private fun showHome(autoSync: Boolean) {
        Sync.schedule(this)
        Notify.showSharing(this)
        val col = ui.column(20)
        col.addView(ui.small("${prefs.fullName ?: ""} · ${prefs.rollNo ?: ""}${prefs.groupName?.let { " · $it" } ?: ""}"))
        col.addView(ui.gap(4))
        col.addView(ui.title("Your digital wellbeing"))
        col.addView(ui.gap(16))

        // SOS first, so it is always one tap away.
        val sosCard = ui.card()
        sosCard.addView(ui.sosButton("SOS") { confirmSos() }, ui.fullWidth())
        sosCard.addView(ui.gap(8))
        sosCard.addView(ui.small(
            if (prefs.sosPhone != null) "In an emergency: calls your mentor (${prefs.sosPhone}) and sends them your location."
            else "In an emergency: alerts your mentor with your location. Your mentor hasn't added a phone number yet."
        ))
        col.addView(sosCard, ui.fullWidth())
        col.addView(ui.gap(12))

        // Anything switched off
        val usageOn = UsageCollector.hasUsageAccess(this)
        val watcherOn = Watcher.isEnabled(this)
        if (!usageOn) {
            col.addView(ui.banner("Usage access is off, so your screen time isn't being shared. Your mentor can see this.",
                "Turn on usage access") { openUsageSettings() }, ui.fullWidth())
            col.addView(ui.gap(12))
        }
        if (!watcherOn) {
            col.addView(ui.banner("App timers and website tracking are off. Your mentor can see this.",
                "Turn on in Accessibility") { openAccessibilitySettings() }, ui.fullWidth())
            col.addView(ui.gap(12))
        }
        if (Build.VERSION.SDK_INT >= 33 && !Notify.allowed(this)) {
            col.addView(ui.banner("Notifications are off, so the quick SOS button isn't in your notification bar.",
                "Turn on notifications") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }, ui.fullWidth())
            col.addView(ui.gap(12))
        }

        val days = if (usageOn) try { UsageCollector.collect(this, 7) } catch (e: Exception) { emptyList() } else emptyList()
        val today = days.lastOrNull()

        val todayCard = ui.card()
        todayCard.addView(ui.small("Screen time today"))
        todayCard.addView(ui.big(Format.duration(today?.screenSec ?: 0)))
        todayCard.addView(ui.gap(8))
        val stats = ui.row()
        fun stat(label: String, value: String) = ui.column().apply {
            addView(ui.heading(value)); addView(ui.small(label))
        }
        fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stats.addView(stat("unlocks", today?.unlocks?.toString() ?: "–"), weight())
        stats.addView(stat("after midnight", Format.duration(today?.nightSec ?: 0)), weight())
        val weekAvg = if (days.isNotEmpty()) days.sumOf { it.screenSec } / days.size else 0
        stats.addView(stat("7-day average", Format.duration(weekAvg)), weight())
        todayCard.addView(stats, ui.fullWidth())
        col.addView(todayCard, ui.fullWidth())
        col.addView(ui.gap(12))

        // App timers set by the mentor
        val limits = prefs.limits
        if (limits.isNotEmpty()) {
            val lc = ui.card()
            lc.addView(ui.heading("App timers from your mentor"))
            lc.addView(ui.gap(4))
            lc.addView(ui.small(if (watcherOn) "When a timer runs out, the app closes until midnight." else "Timers aren't working: turn on WellShare in Accessibility."))
            lc.addView(ui.gap(10))
            for (l in limits.sortedBy { it.name.lowercase() }) {
                val used = today?.apps?.firstOrNull { it.pkg == l.pkg }?.sec ?: 0L
                val allowed = l.minutes * 60L
                val frac = used.toFloat() / allowed
                val r = ui.row()
                r.addView(ui.body(l.name).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(ui.small(
                    if (used >= allowed) "Time's up · opens after midnight"
                    else "${Format.duration(used)} of ${Format.duration(allowed)}",
                    if (used >= allowed) ui.error else ui.muted,
                ).apply { gravity = Gravity.END })
                lc.addView(r, ui.fullWidth())
                lc.addView(ui.gap(4))
                lc.addView(ui.meter(frac, if (frac >= 1f) ui.error else if (frac >= 0.8f) ui.warn else ui.accent))
                lc.addView(ui.gap(10))
            }
            col.addView(lc, ui.fullWidth())
            col.addView(ui.gap(12))
        }

        if (days.isNotEmpty()) {
            val week = ui.card()
            week.addView(ui.heading("Last 7 days"))
            week.addView(ui.gap(12))
            val labels = days.map { it.day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            week.addView(WeekChart(this, ui, days.map { it.screenSec }, labels), ui.fullWidth())
            col.addView(week, ui.fullWidth())
            col.addView(ui.gap(12))
        }

        // Top 5 apps today
        val apps = ui.card()
        apps.addView(ui.heading("Top apps today"))
        apps.addView(ui.gap(10))
        val top = today?.apps?.take(5).orEmpty()
        if (top.isEmpty()) apps.addView(ui.small("No app use recorded yet today."))
        val maxSec = top.firstOrNull()?.sec?.coerceAtLeast(1) ?: 1
        top.forEach { a ->
            val r = ui.row()
            r.addView(ui.body(a.name).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(ui.small(Format.duration(a.sec)).apply { gravity = Gravity.END })
            apps.addView(r, ui.fullWidth())
            apps.addView(ui.gap(4))
            apps.addView(ui.meter(a.sec.toFloat() / maxSec))
            apps.addView(ui.gap(10))
        }
        col.addView(apps, ui.fullWidth())
        col.addView(ui.gap(12))

        // Top 5 websites today
        val webs = ui.card()
        webs.addView(ui.heading("Top websites today"))
        webs.addView(ui.gap(4))
        webs.addView(ui.small("Chrome and similar browsers · site names only"))
        webs.addView(ui.gap(10))
        val sites = SitesStore(this).top(LocalDate.now(), 5)
        if (!watcherOn) webs.addView(ui.small("Website tracking is off."))
        else if (sites.isEmpty()) webs.addView(ui.small("No websites recorded yet today."))
        val maxSite = sites.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
        sites.forEach { (domain, sec) ->
            val r = ui.row()
            r.addView(ui.body(domain).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(ui.small(Format.duration(sec)).apply { gravity = Gravity.END })
            webs.addView(r, ui.fullWidth())
            webs.addView(ui.gap(4))
            webs.addView(ui.meter(sec.toFloat() / maxSite))
            webs.addView(ui.gap(10))
        }
        col.addView(webs, ui.fullWidth())
        col.addView(ui.gap(12))

        val syncLine = ui.small(syncText())
        col.addView(syncLine)
        col.addView(ui.gap(12))
        lateinit var syncBtn: View
        syncBtn = ui.button("Sync now", primary = false) {
            syncBtn.isEnabled = false
            syncLine.text = "Sending…"
            runSync { syncBtn.isEnabled = true; syncLine.text = syncText() }
        }
        col.addView(syncBtn, ui.fullWidth())
        col.addView(ui.gap(16))
        col.addView(ui.small("WellShare shares automatically about every hour when you're online. You don't need to press anything. " +
            "Numbers can differ slightly from Digital Wellbeing because each counts screen-on time a little differently."))
        col.addView(ui.gap(12))
        col.addView(ui.label("Stop sharing on this phone", 14f, ui.error).apply {
            setPadding(0, ui.dp(8), 0, ui.dp(8))
            setOnClickListener { confirmStop() }
        })
        setScreen(col)

        if (autoSync) {
            syncLine.text = "Sending…"
            runSync { syncLine.text = syncText() }
        }
    }

    private fun runSync(done: () -> Unit) {
        Thread {
            Sync.run(this)
            main.post {
                if (sosActive) return@post
                if (!prefs.isRegistered) render() else done()
            }
        }.start()
    }

    private fun syncText(): String {
        prefs.lastError?.let { return it }
        val t = prefs.lastSyncAt
        return if (t == 0L) "Not sent yet."
        else "Last sent to your mentor: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop sharing?")
            .setMessage("This phone will stop sending updates, app timers will stop, and your mentor will see that you stopped. " +
                "They keep what was already sent. To join again later, register with the same roll number.")
            .setPositiveButton("Stop sharing") { _, _ ->
                val token = prefs.token
                val device = prefs.deviceHash
                Thread {
                    if (token != null && device != null) try { Api.reportStopped(token, device) } catch (_: Exception) {}
                    main.post {
                        Sync.cancel(this)
                        Notify.cancel(this)
                        SitesStore(this).clear()
                        prefs.clearRegistration()
                        prefs.consented = false
                        prefs.safetyAsked = false
                        render()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- SOS ----------

    private fun confirmSos() {
        if (!prefs.isRegistered) return
        val who = prefs.sosPhone?.let { "calls your mentor ($it)" } ?: "alerts your mentor"
        AlertDialog.Builder(this)
            .setTitle("Send SOS?")
            .setMessage("This $who and sends them your location right now.")
            .setPositiveButton("Send SOS") { _, _ -> startSos() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSos() {
        val token = prefs.token ?: return
        val device = prefs.deviceHash ?: return
        sosActive = true

        val col = ui.column(24)
        col.addView(ui.label("SOS", 34f, ui.sos, bold = true))
        col.addView(ui.gap(12))
        val alertLine = ui.body("Sending an alert to your mentor…")
        val locLine = ui.small(if (Sos.hasLocation(this)) "Getting your location…" else "Location not shared: WellShare isn't allowed to use location.")
        val seenLine = ui.body("")
        col.addView(alertLine); col.addView(ui.gap(8))
        col.addView(locLine); col.addView(ui.gap(8))
        col.addView(seenLine); col.addView(ui.gap(20))

        val callMentor: Button = ui.sosButton("Call mentor") { prefs.sosPhone?.let { placeCall(it) } }
        col.addView(callMentor, ui.fullWidth())
        col.addView(ui.gap(12))
        col.addView(ui.button("Call ${Sos.EMERGENCY_NUMBER} (emergency services)", primary = false) { placeCall(Sos.EMERGENCY_NUMBER) }, ui.fullWidth())
        col.addView(ui.gap(12))
        col.addView(ui.button("I'm safe now. Close", primary = false) {
            sosActive = false
            render()
        }, ui.fullWidth())
        setScreen(col)

        fun showNumber(n: String?) {
            callMentor.visibility = if (n != null) View.VISIBLE else View.GONE
            callMentor.text = if (n != null) "Call mentor ($n)" else "Call mentor"
        }
        val known = prefs.sosPhone
        showNumber(known)
        // Call straight away if we already know the number; the alert is sent in the background meanwhile.
        if (known != null) placeCall(known)

        Thread {
            val first = Sos.lastKnown(this)
            var sent: Pair<String, String?>? = null
            var error: Api.ApiError? = null
            for (attempt in 0 until 6) {
                try {
                    sent = Api.sendSos(token, device, first); error = null; break
                } catch (e: Api.ApiError) {
                    error = e
                    if (e.code != "NETWORK") break
                    Thread.sleep(5000)
                }
            }
            val result = sent
            if (result == null) {
                if (error?.code == "NETWORK") {
                    prefs.pendingSosAt = System.currentTimeMillis()
                    Sync.schedule(this)
                }
                main.post {
                    alertLine.setTextColor(ui.error)
                    alertLine.text = if (error?.code == "NETWORK")
                        "No internet, so the alert isn't sent yet. WellShare will keep trying for 30 minutes. Call your mentor or ${Sos.EMERGENCY_NUMBER} directly."
                    else error?.message ?: "The alert couldn't be sent."
                    locLine.text = ""
                }
                return@Thread
            }
            val (sosId, phone) = result
            if (phone != null) prefs.sosPhone = phone
            main.post {
                alertLine.text = "Alert sent to your mentor at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                showNumber(phone ?: known)
                if (known == null && phone != null) placeCall(phone)
                if (phone == null && known == null) {
                    seenLine.text = "Your mentor hasn't added a phone number. If you're in danger, call ${Sos.EMERGENCY_NUMBER}."
                }
            }
            // A fresher location follows within ~20 seconds.
            Sos.fresh(this, 20_000) { fix ->
                val best = fix ?: first
                if (fix != null) try { Api.updateSosLocation(token, device, sosId, fix) } catch (_: Exception) {}
                main.post {
                    locLine.text = when {
                        best != null -> "Location shared (accurate to about ${best.accuracy.toInt()} m)."
                        Sos.hasLocation(this) -> "Couldn't find your location. Is location turned on?"
                        else -> "Location not shared: WellShare isn't allowed to use location."
                    }
                }
            }
            // Tell the student when a mentor has seen it (checks for 10 minutes).
            for (i in 0 until 40) {
                if (!sosActive) return@Thread
                try {
                    if (Api.sosSeen(token, device, sosId)) {
                        main.post { seenLine.setTextColor(ui.accent); seenLine.text = "✓ Your mentor has seen your alert." }
                        return@Thread
                    }
                } catch (_: Exception) {}
                Thread.sleep(15_000)
            }
        }.start()
    }

    private fun placeCall(number: String) {
        try {
            startActivity(Sos.callIntent(this, number))
        } catch (_: SecurityException) {
            try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) } catch (_: Exception) {}
        } catch (_: ActivityNotFoundException) {
            toast("This phone can't make calls. Number: $number")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

}
