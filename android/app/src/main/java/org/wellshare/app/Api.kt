package org.wellshare.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the database functions the phone is allowed to call. */
object Api {

    class ApiError(val code: String, message: String) : Exception(message)

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank()

    data class Registration(
        val token: String, val fullName: String, val rollNo: String, val groupName: String,
        val relinked: Boolean, val sosPhone: String?, val limits: List<AppLimit>,
    )

    data class UploadResult(val saved: Int, val limits: List<AppLimit>, val sosPhone: String?, val groupName: String?)

    data class Fix(val lat: Double, val lon: Double, val accuracy: Float, val ageSec: Int)

    fun register(
        joinCode: String, fullName: String, rollNo: String,
        device: DeviceId.Result, model: String, androidVersion: String, appVersion: String,
    ): Registration {
        val body = JSONObject()
            .put("p_join_code", joinCode)
            .put("p_full_name", fullName)
            .put("p_roll_no", rollNo)
            .put("p_device_hash", device.hash)
            .put("p_id_source", device.source)
            .put("p_device_model", model)
            .put("p_android_version", androidVersion)
            .put("p_app_version", appVersion)
        val r = JSONObject(rpc("register_device", body))
        // A wrong group code comes back as {"error": "..."} so the server can count failed guesses.
        r.optString("error").takeIf { it.isNotBlank() }?.let { throw ApiError(it, friendly(it)) }
        return Registration(
            token = r.getString("token"),
            fullName = r.getString("full_name"),
            rollNo = r.getString("roll_no"),
            groupName = r.optString("group_name", ""),
            relinked = r.optBoolean("relinked", false),
            sosPhone = r.optStringOrNull("sos_phone"),
            limits = parseLimits(r.optJSONArray("limits")),
        )
    }

    fun upload(
        token: String, deviceHash: String, days: List<DayUsage>,
        sites: Map<String, List<Pair<String, Long>>>, status: JSONObject, appVersion: String,
    ): UploadResult {
        val arr = JSONArray()
        for (d in days) {
            val apps = JSONArray()
            d.apps.take(20).forEach { a ->
                apps.put(JSONObject().put("pkg", a.pkg).put("name", a.name).put("sec", a.sec).put("opens", a.opens))
            }
            val siteArr = JSONArray()
            sites[d.day.toString()].orEmpty().take(10).forEach { (domain, sec) ->
                siteArr.put(JSONObject().put("domain", domain).put("sec", sec))
            }
            val o = JSONObject()
                .put("day", d.day.toString())
                .put("screen_time_sec", d.screenSec)
                .put("night_sec", d.nightSec)
                .put("apps", apps)
                .put("sites", siteArr)
            if (d.unlocks != null) o.put("unlocks", d.unlocks)
            arr.put(o)
        }
        val body = JSONObject()
            .put("p_token", token)
            .put("p_device_hash", deviceHash)
            .put("p_days", arr)
            .put("p_app_version", appVersion)
            .put("p_status", status)
        val r = JSONObject(rpc("upload_usage", body))
        return UploadResult(
            saved = r.optInt("saved", 0),
            limits = parseLimits(r.optJSONArray("limits")),
            sosPhone = r.optStringOrNull("sos_phone"),
            groupName = r.optStringOrNull("group_name"),
        )
    }

    /** Returns (sosId, phone number to call or null). */
    fun sendSos(token: String, deviceHash: String, fix: Fix?): Pair<String, String?> {
        val body = JSONObject().put("p_token", token).put("p_device_hash", deviceHash)
        if (fix != null) {
            body.put("p_lat", fix.lat).put("p_lon", fix.lon).put("p_accuracy", fix.accuracy.toDouble())
                .put("p_location_age_sec", fix.ageSec)
        }
        val r = JSONObject(rpc("send_sos", body))
        return r.getString("sos_id") to r.optStringOrNull("sos_phone")
    }

    fun updateSosLocation(token: String, deviceHash: String, sosId: String, fix: Fix) {
        rpc("update_sos_location", JSONObject()
            .put("p_token", token).put("p_device_hash", deviceHash).put("p_sos_id", sosId)
            .put("p_lat", fix.lat).put("p_lon", fix.lon).put("p_accuracy", fix.accuracy.toDouble()))
    }

    fun sosSeen(token: String, deviceHash: String, sosId: String): Boolean =
        JSONObject(rpc("sos_status", JSONObject()
            .put("p_token", token).put("p_device_hash", deviceHash).put("p_sos_id", sosId)))
            .optBoolean("acknowledged", false)

    fun reportStopped(token: String, deviceHash: String) {
        rpc("report_stopped", JSONObject().put("p_token", token).put("p_device_hash", deviceHash))
    }

    private fun parseLimits(a: JSONArray?): List<AppLimit> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("pkg")
            val min = o.optInt("minutes", 0)
            if (pkg.isBlank() || min <= 0) null else AppLimit(pkg, o.optString("name").ifBlank { pkg }, min)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun rpc(fn: String, body: JSONObject): String {
        if (!isConfigured) throw ApiError("NOT_CONFIGURED", friendly("NOT_CONFIGURED"))
        val conn = URL("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            // New Supabase keys (sb_publishable_...) go only in "apikey".
            // Older "anon" keys (a JWT starting with eyJ) also need the Authorization header.
            val key = BuildConfig.SUPABASE_KEY
            conn.setRequestProperty("apikey", key)
            if (!key.startsWith("sb_")) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return text

            val serverCode = try { JSONObject(text).optString("message", "HTTP_$code") } catch (_: Exception) { "HTTP_$code" }
            throw ApiError(serverCode, friendly(serverCode))
        } catch (e: IOException) {
            throw ApiError("NETWORK", friendly("NETWORK"))
        } finally {
            conn.disconnect()
        }
    }

    fun friendly(code: String): String = when (code) {
        "INVALID_CODE" -> "That group code doesn't exist. Check it with your mentor."
        "DEVICE_ALREADY_REGISTERED" -> "This phone is already registered to another student. One phone can belong to only one participant. Ask your mentor to reset it if this is a mistake."
        "ROLL_ALREADY_REGISTERED" -> "This roll number is already linked to a different phone. Ask your mentor to reset it if you changed phones."
        "BAD_NAME" -> "Please enter your full name."
        "BAD_ROLL" -> "Please enter your roll number."
        "BAD_DEVICE_ID" -> "This phone couldn't produce a device id."
        "INVALID_TOKEN" -> "Your registration was removed by your mentor. Please register again."
        "DEVICE_MISMATCH" -> "This registration belongs to a different phone. Please register again."
        "TOO_MANY_ATTEMPTS" -> "Too many wrong group codes from this network. Wait 15 minutes, then check the code with your mentor."
        "SOS_RATE_LIMIT" -> "You've sent several SOS alerts in the last few minutes. Your mentor already has them. Call them directly or call 112."
        "NETWORK" -> "Couldn't reach the server. Check your internet (hostel Wi-Fi login page?) and try again."
        "NOT_CONFIGURED" -> "This copy of the app has no server address. Download the official build from your project's Releases page."
        else -> "Something went wrong ($code). Try again later."
    }
}
