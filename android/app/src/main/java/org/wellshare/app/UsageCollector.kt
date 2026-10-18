package org.wellshare.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/**
 * Computes the same kind of numbers Digital Wellbeing shows, from Android's
 * usage event log (UsageStatsManager). Digital Wellbeing itself has no API,
 * but it is built on this same log.
 */
object UsageCollector {

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Returns one entry per day, oldest first, ending with today. */
    fun collect(context: Context, days: Int, withLabels: Boolean = true): List<DayUsage> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firstDay = today.minusDays((days - 1).toLong())
        val rangeStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val raw = usm.queryEvents(rangeStart - UsageMath.LOOKBACK_MS, now)
        val list = ArrayList<UsageMath.Ev>()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val pkg = e.packageName ?: continue
            list.add(UsageMath.Ev(e.eventType, pkg, e.className ?: "", e.timeStamp))
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val labels = HashMap<String, String>()
        return UsageMath.compute(
            events = list,
            firstDay = firstDay,
            days = days,
            zone = zone,
            now = now,
            screenOnNow = pm.isInteractive,
            ignored = ignoredPackages(context),
            unlocksSupported = Build.VERSION.SDK_INT >= 28,
            label = { pkg -> if (withLabels) labels.getOrPut(pkg) { label(context, pkg) } else pkg },
        )
    }

    /** Seconds this app has been on screen today (the same number the dashboard shows). */
    fun todaySeconds(context: Context, pkg: String): Long =
        collect(context, 1, withLabels = false).lastOrNull()?.apps?.firstOrNull { it.pkg == pkg }?.sec ?: 0L

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** Home-screen launchers and system UI are not "apps you used" (Digital Wellbeing skips them too). */
    private fun ignoredPackages(context: Context): Set<String> {
        val set = hashSetOf("com.android.systemui", "android", context.packageName)
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(home, 0).forEach { set.add(it.activityInfo.packageName) }
        } catch (_: Exception) {
        }
        return set
    }

}
