package org.wellshare.app

import java.time.LocalDate
import java.time.ZoneId

data class AppUsage(val pkg: String, val name: String, val sec: Long, val opens: Int)

data class DayUsage(
    val day: LocalDate,
    val screenSec: Long,
    val unlocks: Int?,      // null on Android 8 (not available there)
    val nightSec: Long,     // time used between 00:00 and 05:00
    val apps: List<AppUsage>,
)

/**
 * Pure calculation (no Android calls) so it can be unit-tested on any computer.
 * Turns Android's raw usage events into per-day totals.
 */
object UsageMath {
    // UsageEvents.Event type values (stable across Android versions)
    const val ACTIVITY_RESUMED = 1        // MOVE_TO_FOREGROUND before API 29
    const val ACTIVITY_PAUSED = 2         // MOVE_TO_BACKGROUND before API 29
    const val SCREEN_NON_INTERACTIVE = 16 // API 28
    const val KEYGUARD_HIDDEN = 18        // API 28 — one unlock
    const val ACTIVITY_STOPPED = 23       // API 29
    const val DEVICE_SHUTDOWN = 26        // API 28

    /** Read a little before midnight so an app already open at 00:00 is counted. */
    const val LOOKBACK_MS = 3 * 60 * 60 * 1000L

    data class Ev(val type: Int, val pkg: String, val cls: String, val t: Long)
    private class Session(val pkg: String, val start: Long, val end: Long)

    fun compute(
        events: List<Ev>,
        firstDay: LocalDate,
        days: Int,
        zone: ZoneId,
        now: Long,
        screenOnNow: Boolean,
        ignored: Set<String>,
        unlocksSupported: Boolean,
        label: (String) -> String,
    ): List<DayUsage> {
        val sessions = ArrayList<Session>()
        val unlockTimes = ArrayList<Long>()
        val openActivities = HashMap<String, MutableSet<String>>()
        val fgStart = HashMap<String, Long>()
        val openTimes = ArrayList<Pair<String, Long>>()
        var lastResumedPkg: String? = null

        fun closeAll(t: Long) {
            for ((pkg, start) in fgStart) if (t > start) sessions.add(Session(pkg, start, t))
            fgStart.clear()
            openActivities.clear()
            lastResumedPkg = null
        }

        for (e in events.sortedBy { it.t }) {
            val pkg = e.pkg
            val t = e.t
            when (e.type) {
                ACTIVITY_RESUMED -> {
                    // An "open" = switching to this app from somewhere else
                    // (moving between screens inside the same app is not a new open).
                    val isNewOpen = pkg != lastResumedPkg
                    lastResumedPkg = pkg
                    if (pkg in ignored) continue
                    if (isNewOpen) openTimes.add(pkg to t)
                    val set = openActivities.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty()) fgStart[pkg] = t
                    set.add(e.cls)
                }
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> {
                    val set = openActivities[pkg] ?: continue
                    set.remove(e.cls)
                    if (set.isEmpty()) {
                        openActivities.remove(pkg)
                        fgStart.remove(pkg)?.let { start -> if (t > start) sessions.add(Session(pkg, start, t)) }
                    }
                }
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> closeAll(t)
                KEYGUARD_HIDDEN -> unlockTimes.add(t)
            }
        }
        // Apps still on screen right now count up to "now".
        if (screenOnNow) closeAll(now) else fgStart.clear()

        val result = ArrayList<DayUsage>(days)
        for (i in 0 until days) {
            val day = firstDay.plusDays(i.toLong())
            val dStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val nightEnd = day.atTime(5, 0).atZone(zone).toInstant().toEpochMilli()

            val perAppMs = HashMap<String, Long>()
            val perAppOpens = HashMap<String, Int>()
            var nightMs = 0L
            for (s in sessions) {
                val a = maxOf(s.start, dStart)
                val b = minOf(s.end, dEnd)
                if (b <= a) continue
                perAppMs[s.pkg] = (perAppMs[s.pkg] ?: 0L) + (b - a)
                val nb = minOf(s.end, nightEnd)
                if (nb > a) nightMs += nb - a
            }
            for ((pkg, t) in openTimes) {
                if (t in dStart until dEnd) perAppOpens[pkg] = (perAppOpens[pkg] ?: 0) + 1
            }
            val apps = perAppMs.entries
                .sortedByDescending { it.value }
                .filter { it.value >= 1000 }
                .map { (pkg, ms) -> AppUsage(pkg, label(pkg), ms / 1000, perAppOpens[pkg] ?: 0) }
            result.add(
                DayUsage(
                    day = day,
                    screenSec = minOf(perAppMs.values.sum() / 1000, 86_400L),
                    unlocks = if (unlocksSupported) unlockTimes.count { it in dStart until dEnd } else null,
                    nightSec = minOf(nightMs / 1000, 18_000L),
                    apps = apps,
                )
            )
        }
        return result
    }
}
