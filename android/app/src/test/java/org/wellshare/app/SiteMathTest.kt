package org.wellshare.app

import java.time.LocalDate
import java.time.ZoneId

/**
 * Plain-Kotlin checks for website tracking. Run from the android/ folder:
 *   kotlinc app/src/main/java/org/wellshare/app/SiteMath.kt app/src/test/java/org/wellshare/app/SiteMathTest.kt -include-runtime -d s.jar && java -cp s.jar org.wellshare.app.SiteMathTestKt
 */
private var failed = 0
private fun expect(cond: Boolean, msg: String) {
    if (cond) println("  ok    $msg") else { failed++; println("  FAIL  $msg") }
}

fun main() {
    println("SiteMath.domainOf")
    val cases = listOf(
        "youtube.com" to "youtube.com",
        "https://www.youtube.com/watch?v=abc123" to "youtube.com",
        "m.youtube.com/watch?v=abc" to "youtube.com",
        "en.m.wikipedia.org/wiki/India" to "en.m.wikipedia.org",
        "www.google.com/search?q=how+to+study" to "google.com",
        "HTTP://Example.COM:8080/path" to "example.com",
        "user@mail.example.org/inbox" to "mail.example.org",
        "instagram.com" to "instagram.com",
        "m.facebook.com" to "facebook.com",
        "amp.ndtv.com/news" to "ndtv.com",
        "192.168.1.1/login" to "192.168.1.1",
        "विकिपीडिया.भारत" to "विकिपीडिया.भारत",
    )
    for ((input, want) in cases) {
        val got = SiteMath.domainOf(input)
        expect(got == want, "\"$input\" -> $want (got $got)")
    }
    val rejected = listOf(
        null, "", "   ", "how to cook rice", "Search or type web address", "cats",
        "chrome://newtab", "about:blank", "edge://settings", "file:///sdcard/a.html",
        "3.14", "1.5", "javascript:alert(1)", ".com", "-bad.com",
    )
    for (r in rejected) expect(SiteMath.domainOf(r) == null, "not a site: \"$r\"")
    // Only the site name is ever kept, never the path or the search words
    val leaked = SiteMath.domainOf("google.com/search?q=secret+words")!!
    expect(!leaked.contains("secret") && !leaked.contains("/"), "search words are never kept")

    println("SiteMath.splitByDay")
    val zone = ZoneId.of("Asia/Kolkata")
    fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    val d1 = LocalDate.of(2026, 10, 15)
    val d2 = d1.plusDays(1)
    var parts = SiteMath.splitByDay(at(d1, 10), at(d1, 10, 30), zone)
    expect(parts == listOf(d1 to 1800L), "30 min on one day")
    parts = SiteMath.splitByDay(at(d1, 23, 50), at(d2, 0, 20), zone)
    expect(parts == listOf(d1 to 600L, d2 to 1200L), "23:50→00:20 split 10 min / 20 min")
    expect(SiteMath.splitByDay(at(d1, 10), at(d1, 10), zone).isEmpty(), "zero-length span ignored")
    expect(SiteMath.splitByDay(at(d1, 11), at(d1, 10), zone).isEmpty(), "backwards span ignored")

    println(if (failed == 0) "\nAll checks passed" else "\n$failed check(s) FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
