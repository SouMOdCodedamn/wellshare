package org.wellshare.app

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.text.DateFormat
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Picks the right screen for where the student is in setup. */
    private fun render() {
        when {
            !prefs.consented -> showConsent()
            !UsageCollector.hasUsageAccess(this) -> showUsageAccess()
            !prefs.isRegistered -> showRegister()
            else -> showHome(autoSync = System.currentTimeMillis() - prefs.lastSyncAt > 60 * 60 * 1000L)
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

    // ---------- 1. Consent ----------

    private fun showConsent() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 1 OF 3"))
        col.addView(ui.gap(6))
        col.addView(ui.title("Share your screen time with your mentor"))
        col.addView(ui.gap(12))
        col.addView(ui.body("WellShare sends a daily summary of your phone use to your mentor's dashboard, so you can talk about it together."))
        col.addView(ui.gap(16))

        val shared = ui.card()
        shared.addView(ui.heading("What your mentor sees"))
        shared.addView(ui.gap(8))
        listOf(
            "Total screen time per day",
            "Number of unlocks per day",
            "Time used between midnight and 5 am",
            "Time spent in each of your top 20 apps",
            "Your name, roll number and phone model",
        ).forEach { shared.addView(ui.body("•  $it")) }
        col.addView(shared, ui.fullWidth())
        col.addView(ui.gap(12))

        val notShared = ui.card()
        notShared.addView(ui.heading("Never collected"))
        notShared.addView(ui.gap(8))
        listOf(
            "Messages, photos, contacts or call logs",
            "What you watch, type or browse inside apps",
            "Your location",
        ).forEach { notShared.addView(ui.body("•  $it")) }
        col.addView(notShared, ui.fullWidth())
        col.addView(ui.gap(16))

        val agree = CheckBox(this).apply {
            text = "I agree to share this with my mentor. I can stop at any time by uninstalling the app."
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
        col.addView(ui.small("STEP 2 OF 3"))
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

        // Android 13+ blocks this switch for apps installed from a downloaded APK until the user allows it.
        val blocked = ui.card()
        blocked.addView(ui.heading("Switch greyed out?"))
        blocked.addView(ui.gap(6))
        blocked.addView(ui.body("If you see \"Restricted setting\", Android is being careful because you installed WellShare from a download. To allow it:"))
        blocked.addView(ui.gap(6))
        blocked.addView(ui.body("1.  Tap \"Open app info\" below"))
        blocked.addView(ui.body("2.  Tap ⋮ (top-right corner)"))
        blocked.addView(ui.body("3.  Tap \"Allow restricted settings\" and confirm"))
        blocked.addView(ui.body("4.  Come back and turn on usage access"))
        blocked.addView(ui.gap(12))
        blocked.addView(ui.button("Open app info", primary = false) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                toast("Open Settings > Apps > WellShare")
            }
        }, ui.fullWidth())
        col.addView(blocked, ui.fullWidth())
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

    // ---------- 3. Register ----------

    private fun showRegister() {
        val col = ui.column(24)
        col.addView(ui.small("STEP 3 OF 3"))
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
                    prefs.lastError = null
                    Sync.schedule(this)
                    main.post {
                        if (reg.relinked) toast("Welcome back, ${reg.fullName}. This phone is linked to you again.")
                        showHome(autoSync = true)
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

    // ---------- 4. Home: the student's own report ----------

    private fun showHome(autoSync: Boolean) {
        Sync.schedule(this)
        val col = ui.column(20)
        col.addView(ui.small("${prefs.fullName ?: ""} · ${prefs.rollNo ?: ""}${prefs.groupName?.let { " · $it" } ?: ""}"))
        col.addView(ui.gap(4))
        col.addView(ui.title("Your digital wellbeing"))
        col.addView(ui.gap(16))

        val days = try { UsageCollector.collect(this, 7) } catch (e: Exception) { emptyList() }
        val today = days.lastOrNull()

        val todayCard = ui.card()
        todayCard.addView(ui.small("Screen time today"))
        todayCard.addView(ui.big(Format.duration(today?.screenSec ?: 0)))
        todayCard.addView(ui.gap(8))
        val stats = ui.row()
        fun stat(label: String, value: String) = ui.column().apply {
            addView(ui.heading(value)); addView(ui.small(label))
        }
        val weight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        stats.addView(stat("unlocks", today?.unlocks?.toString() ?: "–"), weight)
        stats.addView(stat("after midnight", Format.duration(today?.nightSec ?: 0)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val weekAvg = if (days.isNotEmpty()) days.sumOf { it.screenSec } / days.size else 0
        stats.addView(stat("7-day average", Format.duration(weekAvg)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        todayCard.addView(stats, ui.fullWidth())
        col.addView(todayCard, ui.fullWidth())
        col.addView(ui.gap(12))

        if (days.isNotEmpty()) {
            val week = ui.card()
            week.addView(ui.heading("Last 7 days"))
            week.addView(ui.gap(12))
            val labels = days.map { it.day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            week.addView(WeekChart(this, ui, days.map { it.screenSec }, labels), ui.fullWidth())
            col.addView(week, ui.fullWidth())
            col.addView(ui.gap(12))
        }

        val apps = ui.card()
        apps.addView(ui.heading("Top apps today"))
        apps.addView(ui.gap(10))
        val top = today?.apps?.take(8).orEmpty()
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

        val syncLine = ui.small(syncText())
        col.addView(syncLine)
        col.addView(ui.gap(12))
        lateinit var syncBtn: View
        syncBtn = ui.button("Send to mentor now", primary = false) {
            syncBtn.isEnabled = false
            syncLine.text = "Sending…"
            runSync { syncBtn.isEnabled = true; syncLine.text = syncText() }
        }
        col.addView(syncBtn, ui.fullWidth())
        col.addView(ui.gap(16))
        col.addView(ui.small("WellShare sends an update about every 4 hours when you're online. " +
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
            .setMessage("This phone will stop sending updates. Your mentor keeps what was already sent. To join again later, register with the same roll number.")
            .setPositiveButton("Stop sharing") { _, _ ->
                Sync.cancel(this)
                prefs.clearRegistration()
                prefs.consented = false
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
