#!/usr/bin/env python3
"""
Static security rules for WellShare. Fails if a change weakens any of them.

  python3 security/check_policy.py

Checks the Android manifest and code, the database schema, the dashboard and the
GitHub workflows. Each rule prints the test ID it belongs to in docs/SECURITY-TESTING.md.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = "{http://schemas.android.com/apk/res/android}"
APP = os.path.join(ROOT, "android/app/src/main")

failures, passes = [], []


def check(test_id, ok, what, detail=""):
    (passes if ok else failures).append((test_id, what, detail))


def read(rel):
    return open(os.path.join(ROOT, rel), encoding="utf-8").read()


# ---------------------------------------------------------------- Android sandbox
def android():
    m = ET.parse(os.path.join(APP, "AndroidManifest.xml")).getroot()
    app = m.find("application")
    check("AND-01", app.get(A + "allowBackup") == "false", "Backups of app data are disabled")
    check("AND-02", app.get(A + "debuggable") in (None, "false"), "App is not debuggable")
    check("AND-03", app.get(A + "usesCleartextTraffic") == "false", "Plain HTTP is disabled in the manifest")
    check("AND-03", app.get(A + "networkSecurityConfig") == "@xml/network_security_config", "Network security config is set")

    nsc = ET.parse(os.path.join(APP, "res/xml/network_security_config.xml")).getroot()
    base = nsc.find("base-config")
    check("AND-03", base is not None and base.get("cleartextTrafficPermitted") == "false", "Network config refuses plain HTTP")
    anchors = [c.get("src") for c in nsc.iter("certificates")]
    check("AND-04", anchors == ["system"], "Only the phone's built-in certificate authorities are trusted", f"found {anchors}")
    check("AND-04", nsc.find("debug-overrides") is None and nsc.find("domain-config") is None,
          "No debug or per-domain exceptions in the network config")

    expected = set(open(os.path.join(ROOT, "security/expected-permissions.txt")).read().split())
    actual = {p.get(A + "name") for p in m.findall("uses-permission")}
    check("AND-05", actual == expected, "Permissions match the reviewed list (security/expected-permissions.txt)",
          f"added: {sorted(actual - expected)} removed: {sorted(expected - actual)}")

    allowed_exported = {
        ".MainActivity": None,  # the launcher screen; anything it does needs a tap by the student
        ".Watcher": "android.permission.BIND_ACCESSIBILITY_SERVICE",  # only the system can bind
    }
    for tag in ("activity", "service", "receiver", "provider", "activity-alias"):
        for c in app.findall(tag):
            name = c.get(A + "name")
            exported = c.get(A + "exported")
            check("AND-06", exported in ("true", "false"), f"{tag} {name} declares android:exported explicitly")
            if exported == "true":
                check("AND-06", name in allowed_exported, f"{tag} {name} may be exported")
                need = allowed_exported.get(name)
                if need:
                    check("AND-06", c.get(A + "permission") == need, f"{name} is protected by {need}")
            if tag == "service" and name == ".SyncJobService":
                check("AND-06", c.get(A + "permission") == "android.permission.BIND_JOB_SERVICE", "Sync job is protected")

    gradle = read("android/app/build.gradle.kts")
    check("ENC-04", re.search(r"isMinifyEnabled\s*=\s*true", gradle) is not None, "Release build is shrunk and obfuscated (R8)")

    code = {p: open(p, encoding="utf-8").read() for p in glob.glob(os.path.join(APP, "java/**/*.kt"), recursive=True)}
    banned = {
        r"\bandroid\.util\.Log\b|\bLog\.[dviwe]\(": "No logging (could leak data into system logs)",
        r"\bWebView\b": "No WebView",
        r"DexClassLoader|PathClassLoader|loadLibrary": "No code loaded at runtime",
        r"MODE_WORLD_(READABLE|WRITEABLE)": "No world-readable files",
        r"getExternalStorage|getExternalFilesDir|MediaStore": "Nothing written to shared storage",
        r"X509TrustManager|HostnameVerifier|setDefaultSSLSocketFactory": "TLS checks are never overridden",
        r"Runtime\.getRuntime\(\)\.exec|ProcessBuilder": "No shell commands",
        r"\"http://": "No plain-HTTP URLs",
    }
    for rx, what in banned.items():
        hits = [os.path.basename(p) for p, src in code.items() if re.search(rx, src)]
        check("AND-07", not hits, what, f"found in {hits}")
    for p, src in code.items():
        for mm in re.finditer(r"PendingIntent\.get(Activity|Service|Broadcast)\(", src):
            window = src[mm.start(): mm.start() + 400]
            check("AND-08", "FLAG_IMMUTABLE" in window, f"PendingIntent in {os.path.basename(p)} is immutable")
    prefs = code[os.path.join(APP, "java/org/wellshare/app/Prefs.kt")]
    check("ENC-03", 'readSecret("token")' in prefs and "Secure.encrypt" in prefs, "Upload token is stored encrypted (Keystore)")
    sites = code[os.path.join(APP, "java/org/wellshare/app/SitesStore.kt")]
    check("ENC-03", "Secure.encrypt" in sites, "Website log is stored encrypted (Keystore)")
    sm = read("android/app/src/main/java/org/wellshare/app/SiteMath.kt")
    check("PRI-02", "never the page, the search words" in sm, "Website tracking keeps site names only")


# ---------------------------------------------------------------- Database
PHONE_FUNCTIONS = {"register_device", "upload_usage", "send_sos", "update_sos_location", "sos_status", "report_stopped"}


def database():
    sql = read("supabase/schema.sql")
    tables = set(re.findall(r"create table if not exists public\.(\w+)", sql))
    for t in sorted(tables):
        check("DB-01", re.search(rf"alter table public\.{t}\s+enable row level security", sql) is not None,
              f"Row Level Security is on for {t}")
        check("DB-02", re.search(rf"revoke all on [^;]*public\.{t}\b[^;]*from anon;", sql) is not None,
              f"Phones (anon) have no direct access to {t}")
    check("DB-02", re.search(r"grant\s+(select|insert|update|delete|all)[^;]*\bto\b[^;]*\banon\b", sql, re.I) is None,
          "No table privileges are granted to anon")
    check("DB-02", re.search(r"\bto\s+public\s*;", sql, re.I) is None, "Nothing is granted to PUBLIC")

    funcs = re.findall(r"create or replace function public\.(\w+)\s*\((.*?)\)\s*returns(.*?)\$\$", sql, re.S)
    for name, _args, body_head in funcs:
        if "security definer" in body_head:
            check("DB-03", "set search_path" in body_head, f"{name}: security definer function pins search_path")
        check("DB-04", re.search(rf"revoke execute on function public\.{name}\(", sql) is not None,
              f"{name}: default EXECUTE permission is revoked")
    anon_grants = set(re.findall(r"grant execute on function public\.(\w+)\([^)]*\)\s+to anon", sql))
    check("DB-04", anon_grants == PHONE_FUNCTIONS, "Phones can call only the phone functions",
          f"extra: {sorted(anon_grants - PHONE_FUNCTIONS)} missing: {sorted(PHONE_FUNCTIONS - anon_grants)}")
    check("DB-04", "grant execute on function public.setup_group" not in sql, "setup_group can only be run from the SQL Editor")
    check("ABU-01", "TOO_MANY_ATTEMPTS" in sql and "register_attempts" in sql, "Group-code guessing is rate-limited")
    check("ABU-02", "SOS_RATE_LIMIT" in sql, "SOS spam is rate-limited")


# ---------------------------------------------------------------- Dashboard
def dashboard():
    html = read("dashboard/index.html")
    build = read("dashboard/build.py")
    inner = re.findall(r"\.innerHTML\s*=\s*([^;]+);", html)
    check("WEB-01", all(x.strip().startswith("ICONS[") for x in inner),
          "innerHTML is only used for built-in icons (all data is shown as text)", f"found {inner}")
    for rx, what in {r"\beval\(": "No eval()", r"new Function\(": "No new Function()",
                     r"document\.write": "No document.write", r"insertAdjacentHTML|outerHTML\s*=": "No other HTML injection"}.items():
        check("WEB-01", re.search(rx, html) is None, what)
    blanks = re.findall(r"\.target\s*=\s*\"_blank\";([^\n]*)", html)
    check("WEB-02", all("noopener" in b for b in blanks), "Links that open new tabs use rel=noopener")
    srcs = re.findall(r"<script[^>]*\bsrc=", html)
    check("WEB-03", not srcs, "No third-party <script src> in the page")
    check("WEB-03", 'SUPABASE_JS = "vendor/supabase.js"' in html, "Sign-in library is served from the same site")
    check("WEB-04", "Content-Security-Policy" in build and "default-src 'none'" in build and "sha256-" in build,
          "Deployed page gets a strict Content-Security-Policy")
    check("WEB-05", "window.top !== window.self" in html, "Dashboard refuses to run inside another site's frame")
    check("KEY-03", "sb_secret_" in build and "service_role" in build, "Deploy refuses a secret key")


# ---------------------------------------------------------------- Supply chain
def workflows():
    for p in sorted(glob.glob(os.path.join(ROOT, ".github/workflows/*.yml"))):
        y = open(p, encoding="utf-8").read()
        n = os.path.basename(p)
        uses = re.findall(r"uses:\s*([^\s#]+)", y)
        unpinned = [u for u in uses if not u.startswith("./") and not re.search(r"@[0-9a-f]{40}$", u)]
        check("SUP-01", not unpinned, f"{n}: every action is pinned to an exact commit", f"unpinned: {unpinned}")
        check("SUP-02", re.search(r"^permissions:", y, re.M) is not None, f"{n}: declares minimal permissions")
        check("SUP-02", "pull_request_target" not in y, f"{n}: no pull_request_target trigger")


def main():
    android(); database(); dashboard(); workflows()
    for tid, what, _ in passes:
        print(f"  ok    [{tid}] {what}")
    for tid, what, detail in failures:
        print(f"  FAIL  [{tid}] {what}" + (f" — {detail}" if detail else ""))
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::error title=Security rule {tid}::{what} {detail}")
    print(f"\n{len(passes)} passed, {len(failures)} failed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
