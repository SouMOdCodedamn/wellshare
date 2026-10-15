package org.wellshare.app

import java.time.LocalDate
import java.time.ZoneId

/**
 * Plain-Kotlin checks for the screen-time math. Run from the android/ folder:
 *   kotlinc app/src/main/java/org/wellshare/app/UsageMath.kt app/src/test/java/org/wellshare/app/UsageMathTest.kt -include-runtime -d t.jar && java -cp t.jar org.wellshare.app.UsageMathTestKt
 */
private val zone = ZoneId.of("Asia/Kolkata")
private val day1 = LocalDate.of(2026, 10, 13)
private val day2 = LocalDate.of(2026, 10, 14)
private fun at(d: LocalDate, h: Int, m: Int = 0, s: Int = 0) = d.atTime(h, m, s).atZone(zone).toInstant().toEpochMilli()

private const val R = UsageMath.ACTIVITY_RESUMED
private const val P = UsageMath.ACTIVITY_PAUSED
private const val S = UsageMath.ACTIVITY_STOPPED
private const val OFF = UsageMath.SCREEN_NON_INTERACTIVE
private const val UNLOCK = UsageMath.KEYGUARD_HIDDEN
private fun ev(type: Int, pkg: String, t: Long, cls: String = "Main") = UsageMath.Ev(type, pkg, cls, t)

private var failures = 0
private fun check(cond: Boolean, msg: String) {
    if (cond) println("  ok    $msg") else { failures++; println("  FAIL  $msg") }
}

private fun run(events: List<UsageMath.Ev>, screenOnNow: Boolean = false, now: Long = at(day2, 23, 59)) =
    UsageMath.compute(events, day1, 2, zone, now, screenOnNow, setOf("launcher"), true) { it.uppercase() }

fun main() {
    println("UsageMath")

    // 1) Simple 30-minute session
    var r = run(listOf(ev(R, "insta", at(day2, 10)), ev(P, "insta", at(day2, 10, 30)), ev(S, "insta", at(day2, 10, 30, 1))))
    check(r[1].screenSec == 1800L && r[1].apps.single().opens == 1, "30 min Instagram = 1800 s, 1 open")
    check(r[1].apps.single().name == "INSTA", "app label is looked up")

    // 2) Moving between two screens of the same app is ONE session and ONE open
    r = run(listOf(
        ev(R, "yt", at(day2, 9), "Home"),
        ev(P, "yt", at(day2, 9, 5), "Home"),
        ev(R, "yt", at(day2, 9, 5), "Player"),
        ev(S, "yt", at(day2, 9, 5, 1), "Home"),
        ev(P, "yt", at(day2, 9, 20), "Player"),
    ))
    check(r[1].screenSec == 1200L, "two screens inside YouTube = 20 min total")
    check(r[1].apps.single().opens == 1, "switching screens inside an app is not a new open")

    // 3) Session crossing midnight is split across the two days, and counts as night use
    r = run(listOf(ev(R, "wa", at(day1, 23, 50)), ev(P, "wa", at(day2, 0, 20))))
    check(r[0].screenSec == 600L && r[1].screenSec == 1200L, "23:50→00:20 splits 10 min / 20 min")
    check(r[1].nightSec == 1200L && r[0].nightSec == 0L, "00:00–00:20 counts as after-midnight use")

    // 4) Screen turning off ends the session even without a pause event
    r = run(listOf(ev(R, "game", at(day2, 14)), ev(OFF, "android", at(day2, 14, 15)), ev(P, "game", at(day2, 16))))
    check(r[1].screenSec == 900L, "screen off at 14:15 stops counting (15 min, not 2 h)")

    // 5) Launcher time is ignored; unlocks are counted per day
    r = run(listOf(
        ev(UNLOCK, "android", at(day1, 8)), ev(UNLOCK, "android", at(day2, 8)), ev(UNLOCK, "android", at(day2, 9)),
        ev(R, "launcher", at(day2, 8)), ev(P, "launcher", at(day2, 8, 10)),
    ))
    check(r[1].screenSec == 0L && r[1].apps.isEmpty(), "home screen time is not counted")
    check(r[0].unlocks == 1 && r[1].unlocks == 2, "unlocks counted per day (1, 2)")

    // 6) Going home and back to the same app counts as a second open
    r = run(listOf(
        ev(R, "chrome", at(day2, 11)), ev(P, "chrome", at(day2, 11, 5)),
        ev(R, "launcher", at(day2, 11, 5)), ev(P, "launcher", at(day2, 11, 6)),
        ev(R, "chrome", at(day2, 11, 6)), ev(P, "chrome", at(day2, 11, 10)),
    ))
    check(r[1].apps.single().opens == 2 && r[1].screenSec == 540L, "home → back again = 2 opens, 9 min")

    // 7) App still open right now counts up to now (only if the screen is on)
    val now = at(day2, 12, 10)
    r = run(listOf(ev(R, "notes", at(day2, 12))), screenOnNow = true, now = now)
    check(r[1].screenSec == 600L, "app open right now counts until now")
    r = run(listOf(ev(R, "notes", at(day2, 12))), screenOnNow = false, now = now)
    check(r[1].screenSec == 0L, "dangling session ignored when the screen is off")

    // 8) Apps are sorted by time, most first; tiny (<1 s) entries dropped
    r = run(listOf(
        ev(R, "a", at(day2, 10)), ev(P, "a", at(day2, 10, 1)),
        ev(R, "b", at(day2, 10, 1)), ev(P, "b", at(day2, 10, 11)),
        ev(R, "c", at(day2, 10, 11)), ev(P, "c", at(day2, 10, 11) + 300),
    ))
    check(r[1].apps.map { it.pkg } == listOf("b", "a"), "apps sorted by time; <1 s dropped")

    println(if (failures == 0) "\nAll checks passed" else "\n$failures check(s) FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
