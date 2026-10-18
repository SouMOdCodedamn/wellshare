package org.wellshare.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure helpers for website tracking (no Android calls, unit-tested). */
object SiteMath {
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://")
    private val HOST_OK = Regex("^[\\p{L}\\p{M}\\p{N}.-]+$")
    private val NOT_WEB = listOf("chrome:", "about:", "edge:", "brave:", "file:", "data:", "content:", "javascript:", "blob:", "chrome-native:")
    private val STRIP = listOf("www.", "m.", "mobile.", "amp.")

    /**
     * Turns what a browser's address bar shows into a site name, e.g.
     * "https://m.youtube.com/watch?v=abc" -> "youtube.com".
     * Returns null for anything that is not a web address (search text, hints, internal pages).
     * Only the site name is kept: never the page, the search words or anything after the "/".
     */
    fun domainOf(raw: String?): String? {
        var s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (NOT_WEB.any { s.startsWith(it) }) return null
        s = s.replace(SCHEME, "")
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var host = if (end >= 0) s.substring(0, end) else s
        host = host.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (host.length < 4 || host.length > 120 || !host.contains('.')) return null
        if (!HOST_OK.matches(host) || host.startsWith('.') || host.startsWith('-')) return null
        // the last part (".com", ".in") must contain a letter, so "1.5" or "3.14" are not sites
        if (host.substringAfterLast('.').none { it.isLetter() } && !isIpv4(host)) return null
        for (p in STRIP) {
            if (host.startsWith(p) && host.count { it == '.' } >= 2) host = host.removePrefix(p)
        }
        return host
    }

    private fun isIpv4(h: String): Boolean {
        val parts = h.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
    }

    /** Splits a time span across local calendar days: [(day, seconds)]. */
    fun splitByDay(startMs: Long, endMs: Long, zone: ZoneId): List<Pair<LocalDate, Long>> {
        if (endMs <= startMs) return emptyList()
        val out = ArrayList<Pair<LocalDate, Long>>()
        var day = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        var cur = startMs
        while (cur < endMs) {
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segEnd = minOf(endMs, dayEnd)
            val sec = (segEnd - cur) / 1000
            if (sec > 0) out.add(day to sec)
            cur = segEnd
            day = day.plusDays(1)
        }
        return out
    }
}
