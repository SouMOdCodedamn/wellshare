package org.wellshare.app

/**
 * Plain-Kotlin checks for in-app updates. Run from the android/ folder:
 *   kotlinc app/src/main/java/org/wellshare/app/UpdateMath.kt app/src/test/java/org/wellshare/app/UpdateMathTest.kt -include-runtime -d u.jar && java -cp u.jar org.wellshare.app.UpdateMathTestKt
 */
private var failed = 0
private fun expect(cond: Boolean, msg: String) {
    if (cond) println("  ok    $msg") else { failed++; println("  FAIL  $msg") }
}

private const val URL = "https://github.com/SouMOdCodedamn/wellshare/releases/latest/download/update.json"
private val SHA = "a".repeat(64)
private val good = UpdateMath.Info(
    versionCode = 40, versionName = "0.4.0",
    apkUrl = "https://github.com/SouMOdCodedamn/wellshare/releases/download/v0.4.0/WellShare-0.4.0.apk",
    sha256 = SHA, size = 250_000, certSha256 = "b".repeat(64), notes = "",
)

fun main() {
    println("UpdateMath.releasesBase / releasesPage")
    expect(UpdateMath.releasesBase(URL) == "https://github.com/SouMOdCodedamn/wellshare/releases/", "finds the project's releases address")
    expect(UpdateMath.releasesPage(URL) == "https://github.com/SouMOdCodedamn/wellshare/releases/latest", "download page for a manual install")
    expect(UpdateMath.releasesBase("") == null, "no address: updates switched off")
    expect(UpdateMath.releasesBase(URL.replace("https://", "http://")) == null, "plain HTTP address refused")
    expect(UpdateMath.releasesBase(URL.replace("github.com", "github.com.evil.example")) == null, "look-alike host refused")
    expect(UpdateMath.releasesBase("https://example.com/o/r/releases/latest/download/update.json") == null, "other website refused")

    println("UpdateMath.allowedHost (every address, including redirects)")
    expect(UpdateMath.allowedHost("https://github.com/o/r/releases/download/v1/WellShare-1.apk"), "github.com allowed")
    expect(UpdateMath.allowedHost("https://objects.githubusercontent.com/github-production-release-asset/1"), "GitHub file server allowed")
    expect(UpdateMath.allowedHost("https://release-assets.githubusercontent.com/x?sig=1"), "GitHub release file server allowed")
    expect(!UpdateMath.allowedHost("http://github.com/o/r"), "plain HTTP refused")
    expect(!UpdateMath.allowedHost("https://evil.example/WellShare.apk"), "other website refused")
    expect(!UpdateMath.allowedHost("https://github.com.evil.example/x"), "look-alike host refused")
    expect(!UpdateMath.allowedHost("https://githubusercontent.com.evil.example/x"), "look-alike file server refused")
    expect(!UpdateMath.allowedHost("https://evilgithubusercontent.com/x"), "suffix trick refused")
    expect(!UpdateMath.allowedHost("https://user:pass@github.com/x"), "address with a login refused")
    expect(!UpdateMath.allowedHost("https://github.com:8443/x"), "unusual port refused")
    expect(!UpdateMath.allowedHost("not a url"), "garbage refused")

    println("UpdateMath.problem (update.json)")
    expect(UpdateMath.problem(good, URL) == null, "a correct update.json passes")
    expect(UpdateMath.problem(good.copy(certSha256 = null), URL) == null, "certificate is optional")
    expect(UpdateMath.problem(good, "") != null, "refused when updates aren't set up")
    expect(UpdateMath.problem(good.copy(apkUrl = "https://evil.example/WellShare.apk"), URL) != null, "app file on another website refused")
    expect(UpdateMath.problem(good.copy(apkUrl = "https://github.com/someone-else/wellshare/releases/download/v1/WellShare.apk"), URL) != null, "app file from another GitHub project refused")
    expect(UpdateMath.problem(good.copy(apkUrl = good.apkUrl.replace("https://", "http://")), URL) != null, "plain HTTP app file refused")
    expect(UpdateMath.problem(good.copy(apkUrl = "https://github.com/SouMOdCodedamn/wellshare/releases/download/../../x/WellShare.apk"), URL) != null, "path tricks refused")
    expect(UpdateMath.problem(good.copy(apkUrl = good.apkUrl.removeSuffix(".apk") + ".zip"), URL) != null, "only .apk files")
    expect(UpdateMath.problem(good.copy(sha256 = "xyz"), URL) != null, "bad checksum refused")
    expect(UpdateMath.problem(good.copy(sha256 = ""), URL) != null, "missing checksum refused")
    expect(UpdateMath.problem(good.copy(size = 0), URL) != null, "zero size refused")
    expect(UpdateMath.problem(good.copy(size = UpdateMath.MAX_APK_BYTES + 1), URL) != null, "huge file refused")
    expect(UpdateMath.problem(good.copy(versionCode = 0), URL) != null, "missing version number refused")
    expect(UpdateMath.problem(good.copy(versionName = "1.0<script>"), URL) != null, "odd version name refused")
    expect(UpdateMath.problem(good.copy(certSha256 = "zz"), URL) != null, "bad certificate refused")

    println("UpdateMath.isNewer (never go back)")
    expect(UpdateMath.isNewer(39, good), "newer version is offered")
    expect(!UpdateMath.isNewer(40, good), "same version is not reinstalled")
    expect(!UpdateMath.isNewer(41, good), "older version is never installed")

    println("UpdateMath.sameSigner")
    val mine = setOf("b".repeat(64))
    expect(UpdateMath.sameSigner(mine, setOf("b".repeat(64))), "same key accepted")
    expect(!UpdateMath.sameSigner(mine, setOf("c".repeat(64))), "different key refused")
    expect(UpdateMath.sameSigner(mine, setOf("c".repeat(64), "b".repeat(64))), "key rotation (old key in history) accepted")
    expect(UpdateMath.sameSigner(mine, emptySet()), "unknown: left to Android, which checks the key itself")

    println("UpdateMath hashing")
    expect(UpdateMath.sha256("abc".toByteArray()) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256 of \"abc\"")
    expect(UpdateMath.normalizeHex("AB:cd:EF") == "abcdef", "certificate digests are normalised")
    expect(UpdateMath.normalizeHex("  ") == null, "empty digest is null")

    println(if (failed == 0) "\nAll update checks passed" else "\n$failed update check(s) FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
