package org.wellshare.app

import java.net.URI
import java.security.MessageDigest

/**
 * Pure helpers for in-app updates (no Android calls, unit-tested).
 *
 * An update is described by update.json, attached to each GitHub release:
 *   {"version_code": 42, "version_name": "0.4.0",
 *    "apk": "https://github.com/OWNER/REPO/releases/download/v0.4.0/WellShare-0.4.0.apk",
 *    "sha256": "<64 hex>", "size": 123456, "cert_sha256": "<64 hex>", "notes": "..."}
 */
object UpdateMath {
    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val certSha256: String?,
        val notes: String,
    )

    const val MAX_APK_BYTES = 60L * 1024 * 1024
    const val MAX_INFO_BYTES = 64 * 1024

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val VERSION_NAME = Regex("^[0-9A-Za-z.+-]{1,32}$")
    private val UPDATE_URL = Regex("^(https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/)latest/download/update\\.json$")
    private val FILE_PART = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk$")

    /**
     * "https://github.com/o/r/releases/latest/download/update.json" -> "https://github.com/o/r/releases/".
     * Null if the address isn't a GitHub release address (then updates are switched off).
     */
    fun releasesBase(updateUrl: String): String? = UPDATE_URL.find(updateUrl)?.groupValues?.get(1)

    /** Page a student can open to download the app by hand. */
    fun releasesPage(updateUrl: String): String? = releasesBase(updateUrl)?.let { it + "latest" }

    /**
     * Every address used while fetching an update (including redirects) must be HTTPS and on
     * GitHub: the release page itself, or GitHub's file servers that it redirects to.
     */
    fun allowedHost(url: String): Boolean {
        val u = try { URI(url) } catch (_: Exception) { return false }
        if (u.scheme != "https" || u.rawUserInfo != null) return false
        if (u.port != -1 && u.port != 443) return false
        val h = u.host?.lowercase() ?: return false
        return h == "github.com" || h.endsWith(".githubusercontent.com")
    }

    /** Checks update.json. Returns what is wrong with it, or null if it is fine. */
    fun problem(info: Info, updateUrl: String): String? {
        val base = releasesBase(updateUrl) ?: return "updates are not set up for this app"
        if (info.versionCode <= 0) return "bad version number"
        if (!VERSION_NAME.matches(info.versionName)) return "bad version name"
        val prefix = base + "download/"
        if (!info.apkUrl.startsWith(prefix) || !FILE_PART.matches(info.apkUrl.removePrefix(prefix))) {
            return "the app file is not in this project's releases"
        }
        if (!HEX64.matches(info.sha256)) return "bad checksum"
        if (info.size < 1 || info.size > MAX_APK_BYTES) return "bad file size"
        if (info.certSha256 != null && !HEX64.matches(info.certSha256)) return "bad signing certificate"
        return null
    }

    /** Only ever move forward: never reinstall the same version or go back to an older one. */
    fun isNewer(installedCode: Long, info: Info): Boolean = info.versionCode > installedCode

    /** Lower-case hex without separators ("AB:CD" -> "abcd"), or null for empty input. */
    fun normalizeHex(s: String?): String? =
        s?.trim()?.lowercase()?.replace(":", "")?.takeIf { it.isNotEmpty() }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * The new file must be signed by a key the installed app was signed with.
     * Empty sets mean "Android didn't tell us"; Android itself still refuses a different key.
     */
    fun sameSigner(installed: Set<String>, candidate: Set<String>): Boolean =
        installed.isEmpty() || candidate.isEmpty() || candidate.any { it in installed }
}
