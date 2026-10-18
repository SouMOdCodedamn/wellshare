package org.wellshare.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A daily app timer set by the mentor. */
data class AppLimit(val pkg: String, val name: String, val minutes: Int)

/** Small key-value store kept only on this phone (never backed up). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("wellshare", Context.MODE_PRIVATE)

    var consented: Boolean
        get() = sp.getBoolean("consented", false)
        set(v) = sp.edit().putBoolean("consented", v).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var deviceHash: String?
        get() = sp.getString("device_hash", null)
        set(v) = sp.edit().putString("device_hash", v).apply()

    var fullName: String?
        get() = sp.getString("full_name", null)
        set(v) = sp.edit().putString("full_name", v).apply()

    var rollNo: String?
        get() = sp.getString("roll_no", null)
        set(v) = sp.edit().putString("roll_no", v).apply()

    var groupName: String?
        get() = sp.getString("group_name", null)
        set(v) = sp.edit().putString("group_name", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0L)
        set(v) = sp.edit().putLong("last_sync_at", v).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    /** Number the SOS button calls (set by the mentor on the dashboard). */
    var sosPhone: String?
        get() = sp.getString("sos_phone", null)
        set(v) = sp.edit().putString("sos_phone", v).apply()

    /** The student has seen the "SOS and notifications" setup step. */
    var safetyAsked: Boolean
        get() = sp.getBoolean("safety_asked", false)
        set(v) = sp.edit().putBoolean("safety_asked", v).apply()

    /** An SOS that could not be sent yet (no internet); sent with the next sync. */
    var pendingSosAt: Long
        get() = sp.getLong("pending_sos_at", 0L)
        set(v) = sp.edit().putLong("pending_sos_at", v).apply()

    var limits: List<AppLimit>
        get() = try {
            val a = JSONArray(sp.getString("limits", "[]") ?: "[]")
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val pkg = o.optString("pkg")
                val min = o.optInt("minutes", 0)
                if (pkg.isBlank() || min <= 0) null else AppLimit(pkg, o.optString("name").ifBlank { pkg }, min)
            }
        } catch (_: Exception) {
            emptyList()
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("pkg", it.pkg).put("name", it.name).put("minutes", it.minutes)) }
            sp.edit().putString("limits", a.toString()).apply()
        }

    val isRegistered: Boolean get() = !token.isNullOrBlank() && !deviceHash.isNullOrBlank()

    fun clearRegistration() {
        sp.edit()
            .remove("token").remove("full_name").remove("roll_no")
            .remove("group_name").remove("last_sync_at").remove("last_error")
            .remove("limits").remove("sos_phone").remove("pending_sos_at")
            .apply()
    }
}
