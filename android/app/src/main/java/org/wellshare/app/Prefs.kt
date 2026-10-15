package org.wellshare.app

import android.content.Context
import android.content.SharedPreferences

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

    val isRegistered: Boolean get() = !token.isNullOrBlank() && !deviceHash.isNullOrBlank()

    fun clearRegistration() {
        sp.edit()
            .remove("token").remove("full_name").remove("roll_no")
            .remove("group_name").remove("last_sync_at").remove("last_error")
            .apply()
    }
}
