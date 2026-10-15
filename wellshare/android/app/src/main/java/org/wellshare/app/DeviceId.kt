package org.wellshare.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * One stable id per physical phone.
 *
 * Primary source: the Widevine DRM device id. It is the same for every user
 * profile, work profile and "second space" on a phone, and survives app
 * re-installs — so a second profile on the same phone cannot register as a
 * different student.
 *
 * Fallback (rare phones without Widevine): ANDROID_ID, which is weaker
 * (it differs per user profile). The mentor dashboard marks these devices.
 *
 * Only a SHA-256 hash is ever sent, salted with the app name, so the raw
 * hardware id never leaves the phone and can't be matched with other apps.
 */
object DeviceId {
    private val WIDEVINE: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    data class Result(val hash: String, val source: String)

    fun get(context: Context): Result {
        drmId()?.let { return Result(sha256("wellshare-drm:$it"), "drm") }
        return Result(sha256("wellshare-aid:${androidId(context)}"), "android_id")
    }

    private fun drmId(): String? {
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE)
            val bytes = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            if (bytes.isEmpty()) null else bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 28) drm?.close() else @Suppress("DEPRECATION") drm?.release()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
