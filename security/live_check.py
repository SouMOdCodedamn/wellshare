#!/usr/bin/env python3
"""
Black-box check of the LIVE setup, using only what an outsider has: the public key that is
inside every copy of the app. Read-only: it never creates or changes data.

  SUPABASE_URL=... SUPABASE_KEY=... DASHBOARD_URL=https://you.github.io/wellshare/ python3 security/live_check.py

(The wrong-group-code test is counted as one failed attempt for the computer running it.)
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
KEY = os.environ.get("SUPABASE_KEY", "")
DASH = os.environ.get("DASHBOARD_URL", "")
if not URL or not KEY:
    print("Set SUPABASE_URL and SUPABASE_KEY."); sys.exit(2)

results = []


def record(tid, ok, what, detail="", warn=False):
    results.append(("PASS" if ok else ("WARN" if warn else "FAIL"), tid, what, detail))


def call(method, path, body=None, extra=None):
    h = {"apikey": KEY, "Content-Type": "application/json", "Accept": "application/json"}
    if not KEY.startswith("sb_"):
        h["Authorization"] = "Bearer " + KEY
    h.update(extra or {})
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(URL + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def denied(status, text):
    return status in (401, 403, 404) or (status == 200 and text.strip() in ("[]", ""))


TABLES = ["groups", "mentors", "participants", "device_secrets", "daily_usage", "app_limits", "sos_events", "register_attempts"]
for t in TABLES:
    s, txt = call("GET", f"/rest/v1/{t}?select=*&limit=1")
    record("LIVE-01", denied(s, txt), f"Public key cannot read table {t}", f"HTTP {s} {txt[:120]}")
for t in ["daily_usage", "participants", "sos_events"]:
    s, txt = call("POST", f"/rest/v1/{t}", {"participant_id": str(uuid.uuid4())}, {"Prefer": "return=minimal"})
    record("LIVE-02", s in (401, 403), f"Public key cannot write table {t}", f"HTTP {s} {txt[:120]}")

for fn, args in {
    "mentor_overview": {"p_group": str(uuid.uuid4()), "p_today": "2026-01-01", "p_days": 7},
    "mentor_sos": {"p_group": str(uuid.uuid4()), "p_days": 7},
    "mentor_set_limit": {"p_group": str(uuid.uuid4()), "p_participant": None, "p_pkg": "a.b.c", "p_name": "x", "p_minutes": 5},
    "mentor_set_sos_phone": {"p_group": str(uuid.uuid4()), "p_phone": "+911234567890"},
    "setup_group": {"p_name": "x", "p_join_code": "x", "p_mentor_email": "x@example.com"},
    "_phone_participant": {"p_token": "x", "p_device_hash": "x"},
    "_hash_token": {"t": "x"},
}.items():
    s, txt = call("POST", f"/rest/v1/rpc/{fn}", args)
    record("LIVE-03", s in (401, 403, 404), f"Public key cannot call {fn}", f"HTTP {s} {txt[:120]}")

fake = "0" * 64
s, txt = call("POST", "/rest/v1/rpc/upload_usage", {"p_token": "not-a-real-token", "p_device_hash": fake, "p_days": []})
record("LIVE-04", s >= 400 and "INVALID_TOKEN" in txt, "Uploads need a valid phone token", f"HTTP {s} {txt[:120]}")
s, txt = call("POST", "/rest/v1/rpc/send_sos", {"p_token": "not-a-real-token", "p_device_hash": fake})
record("LIVE-04", s >= 400 and "INVALID_TOKEN" in txt, "SOS needs a valid phone token", f"HTTP {s} {txt[:120]}")
s, txt = call("POST", "/rest/v1/rpc/register_device",
              {"p_join_code": "ZZ" + uuid.uuid4().hex[:10], "p_full_name": "Security Check", "p_roll_no": "X", "p_device_hash": fake})
if s == 200 and '"INVALID_CODE"' in txt:
    record("LIVE-05", True, "Wrong group codes are refused and counted (rate limit active)")
elif "INVALID_CODE" in txt:
    record("LIVE-05", False, "Wrong group codes are refused, but the rate limit is missing: run the latest schema.sql", txt[:120], warn=True)
else:
    record("LIVE-05", False, "Wrong group code was not refused", f"HTTP {s} {txt[:120]}")

s, txt = call("GET", "/auth/v1/settings")
try:
    signup_off = json.loads(txt).get("disable_signup") is True
except Exception:
    signup_off = False
record("LIVE-06", signup_off, "Public sign-ups are switched off (only the admin creates mentor logins)",
       "Supabase > Authentication > Sign In / Providers > turn off 'Allow new users to sign up'", warn=True)

if DASH:
    try:
        with urllib.request.urlopen(DASH, timeout=30) as r:
            html = r.read().decode("utf-8", "replace")
        record("LIVE-07", DASH.startswith("https://"), "Dashboard is served over HTTPS")
        record("LIVE-07", "Content-Security-Policy" in html and f"connect-src {URL}" in html,
               "Dashboard has the strict Content-Security-Policy for this project")
        record("LIVE-07", "__SUPABASE" not in html, "Dashboard has its settings filled in (not demo mode)")
        record("LIVE-07", "sb_secret_" not in html, "No secret key in the dashboard")
    except Exception as e:
        record("LIVE-07", False, "Dashboard could not be loaded", str(e))

bad = [r for r in results if r[0] == "FAIL"]
lines = ["| Result | Test | What | Detail |", "|---|---|---|---|"]
for st, tid, what, detail in results:
    icon = {"PASS": "✅", "WARN": "⚠️", "FAIL": "❌"}[st]
    lines.append(f"| {icon} {st} | {tid} | {what} | {detail.replace('|', '/')[:100] if st != 'PASS' else ''} |")
    print(f"  {st:4}  [{tid}] {what}" + (f" — {detail}" if st != "PASS" and detail else ""))
    if os.environ.get("GITHUB_ACTIONS") and st != "PASS":
        level = "error" if st == "FAIL" else "warning"
        print(f"::{level} title=Live check {tid}::{what} — {detail[:200]}")
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as f:
        f.write("## Live security check\n\n" + "\n".join(lines) + "\n")
print(f"\n{sum(r[0] == 'PASS' for r in results)} passed, {sum(r[0] == 'WARN' for r in results)} warnings, {len(bad)} failed")
sys.exit(1 if bad else 0)
