package org.wellshare.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the two Supabase database functions the phone is allowed to call. */
object Api {

    class ApiError(val code: String, message: String) : Exception(message)

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank()

    data class Registration(val token: String, val fullName: String, val rollNo: String, val groupName: String, val relinked: Boolean)

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
        return Registration(
            token = r.getString("token"),
            fullName = r.getString("full_name"),
            rollNo = r.getString("roll_no"),
            groupName = r.optString("group_name", ""),
            relinked = r.optBoolean("relinked", false),
        )
    }

    fun upload(token: String, deviceHash: String, days: List<DayUsage>, appVersion: String): Int {
        val arr = JSONArray()
        for (d in days) {
            val apps = JSONArray()
            d.apps.take(20).forEach { a ->
                apps.put(JSONObject().put("pkg", a.pkg).put("name", a.name).put("sec", a.sec).put("opens", a.opens))
            }
            val o = JSONObject()
                .put("day", d.day.toString())
                .put("screen_time_sec", d.screenSec)
                .put("night_sec", d.nightSec)
                .put("apps", apps)
            if (d.unlocks != null) o.put("unlocks", d.unlocks)
            arr.put(o)
        }
        val body = JSONObject()
            .put("p_token", token)
            .put("p_device_hash", deviceHash)
            .put("p_days", arr)
            .put("p_app_version", appVersion)
        return JSONObject(rpc("upload_usage", body)).optInt("saved", 0)
    }

    private fun rpc(fn: String, body: JSONObject): String {
        if (!isConfigured) throw ApiError("NOT_CONFIGURED", "This copy of the app has no server address.")
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
            throw ApiError("NETWORK", "Couldn't reach the server. Check your internet (hostel Wi-Fi login page?) and try again.")
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
        "NOT_CONFIGURED" -> "This copy of the app has no server address. Download the official build from your project's Releases page."
        else -> "Something went wrong ($code). Try again later."
    }
}
