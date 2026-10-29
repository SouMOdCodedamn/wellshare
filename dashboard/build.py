#!/usr/bin/env python3
"""
Builds the mentor dashboard for GitHub Pages.

  python3 dashboard/build.py --out _site --vendor path/to/supabase.js

Reads SUPABASE_URL and SUPABASE_KEY from the environment (GitHub secrets). Without them the
page runs in demo mode. Adds a strict Content-Security-Policy: the page may only run its own
script (pinned by hash) and the sign-in library served from the same site, and may only talk
to your Supabase project.
"""
import argparse
import base64
import hashlib
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
URL_OK = re.compile(r"^https://[a-z0-9]{10,40}\.supabase\.co$")
KEY_OK = re.compile(r"^[A-Za-z0-9._-]{20,400}$")


def fail(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="_site")
    ap.add_argument("--vendor", help="supabase.js UMD build (from npm)")
    args = ap.parse_args()

    url = os.environ.get("SUPABASE_URL", "").strip().rstrip("/")
    key = os.environ.get("SUPABASE_KEY", "").strip()
    live = bool(url and key)
    if live:
        if not URL_OK.match(url):
            fail("SUPABASE_URL must look like https://<project-id>.supabase.co (no path, no trailing slash).")
        if not KEY_OK.match(key):
            fail("SUPABASE_KEY has unexpected characters. Use the publishable (sb_publishable_...) or anon key.")
        if key.startswith("sb_secret_") or '"service_role"' in _jwt_payload(key):
            fail("SUPABASE_KEY is a SECRET key. Never put it in the dashboard. Use the publishable key.")
        if not args.vendor or not os.path.isfile(args.vendor):
            fail("--vendor supabase.js is required for a live build.")
    else:
        print("::warning::SUPABASE_URL / SUPABASE_KEY are missing, so the dashboard will show demo data.")

    src = open(os.path.join(HERE, "index.html"), encoding="utf-8").read()
    os.makedirs(os.path.join(args.out, "vendor"), exist_ok=True)

    sri = ""
    if args.vendor and os.path.isfile(args.vendor):
        data = open(args.vendor, "rb").read()
        sri = "sha384-" + base64.b64encode(hashlib.sha384(data).digest()).decode()
        shutil.copyfile(args.vendor, os.path.join(args.out, "vendor", "supabase.js"))

    src = (src.replace("__SUPABASE_URL__", url if live else "")
              .replace("__SUPABASE_KEY__", key if live else "")
              .replace("__SUPABASE_JS_SRI__", sri))

    scripts = re.findall(r"<script>(.*?)</script>", src, flags=re.S)
    if len(scripts) != 1:
        fail(f"Expected exactly one inline <script> in index.html, found {len(scripts)}.")
    script_hash = base64.b64encode(hashlib.sha256(scripts[0].encode("utf-8")).digest()).decode()

    csp = "; ".join([
        "default-src 'none'",
        f"script-src 'self' 'sha256-{script_hash}'",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src https://fonts.gstatic.com",
        "img-src 'self' data:",
        f"connect-src {url if live else chr(39) + 'none' + chr(39)}",
        "base-uri 'none'",
        "form-action 'none'",
        "object-src 'none'",
    ])
    head = (
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
        f"<meta http-equiv=\"Content-Security-Policy\" content=\"{csp}\">\n"
        "<meta name=\"referrer\" content=\"no-referrer\">\n"
        "<meta name=\"robots\" content=\"noindex\">\n"
    )
    with open(os.path.join(args.out, "index.html"), "w", encoding="utf-8") as f:
        f.write(head + src + "\n</html>\n")
    open(os.path.join(args.out, ".nojekyll"), "w").close()
    print(f"Built {'live' if live else 'demo'} dashboard. CSP script hash sha256-{script_hash}"
          + (f"; library {sri}" if sri else ""))


def _jwt_payload(token):
    """Decodes the middle part of a JWT-style key (legacy anon/service_role keys), or ''."""
    parts = token.split(".")
    if len(parts) != 3:
        return ""
    try:
        pad = "=" * (-len(parts[1]) % 4)
        return base64.urlsafe_b64decode(parts[1] + pad).decode("utf-8", "replace")
    except Exception:
        return ""


if __name__ == "__main__":
    main()
