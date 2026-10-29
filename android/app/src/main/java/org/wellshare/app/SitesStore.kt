package org.wellshare.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Seconds spent on each website per day, kept on the phone for 10 days.
 * Only site names are stored (e.g. "youtube.com").
 */
class SitesStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("wellshare_sites", Context.MODE_PRIVATE)

    @Synchronized
    fun add(startMs: Long, endMs: Long, domain: String) {
        val parts = SiteMath.splitByDay(startMs, endMs, ZoneId.systemDefault())
        if (parts.isEmpty()) return
        val all = load()
        for ((day, sec) in parts) {
            val key = day.toString()
            val m = all.optJSONObject(key) ?: JSONObject().also { all.put(key, it) }
            m.put(domain, m.optLong(domain, 0L) + sec)
        }
        // keep the last 10 days only
        val oldest = LocalDate.now().minusDays(9).toString()
        val keys = all.keys().asSequence().toList()
        for (k in keys) if (k < oldest) all.remove(k)
        sp.edit().putString("days", Secure.encrypt(all.toString())).apply()
    }

    @Synchronized
    fun top(day: LocalDate, n: Int): List<Pair<String, Long>> {
        val m = load().optJSONObject(day.toString()) ?: return emptyList()
        return m.keys().asSequence()
            .map { it to m.optLong(it, 0L) }
            .filter { it.second >= 5 }
            .sortedByDescending { it.second }
            .take(n)
            .toList()
    }

    @Synchronized
    fun clear() = sp.edit().clear().apply()

    /** Stored encrypted (see Secure); older plain-text logs are still read. */
    private fun load(): JSONObject = try {
        JSONObject(Secure.decrypt(sp.getString("days", null)) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }
}
