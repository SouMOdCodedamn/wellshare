#!/usr/bin/env python3
"""
Looks for secrets that must never be in the code, the app or the website.

  python3 security/scan_secrets.py                 # every file tracked by git
  python3 security/scan_secrets.py --dir PATH --built   # a built APK (unzipped) or website

In --built mode the Supabase *publishable/anon* key is expected (it is meant to be public);
anything else below is a failure everywhere.
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys

TEXT_LIMIT = 30 * 1024 * 1024

RULES = [
    ("Supabase secret key", re.compile(rb"sb_secret_[A-Za-z0-9_-]{10,}")),
    ("Private key", re.compile(rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("Database URL with password", re.compile(rb"postgres(?:ql)?://[^:\s/]+:[^@\s/]{4,}@[^\s]+")),
    ("GitHub token", re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("Google API key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Password in code", re.compile(rb"(?i)\b(?:password|passwd|secret)\b[\"']?\s*[:=]\s*[\"'][^\"'\s]{6,}[\"']")),
]
JWT = re.compile(rb"\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")
FORBIDDEN_FILES = re.compile(r"(?i)(\.jks|\.keystore|\.p12|\.pfx|\.pem|\.key|(^|/)\.env(\..*)?|(^|/)local\.properties|google-services\.json)$")


def jwt_role(token: bytes) -> str:
    try:
        part = token.split(b".")[1]
        part += b"=" * (-len(part) % 4)
        return str(json.loads(base64.urlsafe_b64decode(part)).get("role", "?"))
    except Exception:
        return "?"


def files_in_git():
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
    return [p for p in out.decode().split("\0") if p]


def files_in_dir(d):
    for root, _, names in os.walk(d):
        for n in names:
            yield os.path.join(root, n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", help="scan this folder instead of the git repository")
    ap.add_argument("--built", action="store_true", help="a built app or site: the public anon key is allowed")
    args = ap.parse_args()

    paths = list(files_in_dir(args.dir)) if args.dir else files_in_git()
    problems = []
    for p in paths:
        rel = os.path.relpath(p, args.dir) if args.dir else p
        if not args.dir and FORBIDDEN_FILES.search(rel) and not rel.endswith(".example"):
            problems.append((rel, "File type that holds secrets is tracked by git"))
        try:
            if os.path.getsize(p) > TEXT_LIMIT:
                continue
            data = open(p, "rb").read()
        except OSError:
            continue
        for name, rx in RULES:
            for m in rx.finditer(data):
                problems.append((rel, f"{name}: {m.group(0)[:12].decode('latin1')}…"))
        for m in JWT.finditer(data):
            role = jwt_role(m.group(0))
            if role == "anon" and args.built:
                continue
            problems.append((rel, f"JWT key with role '{role}'" + (" (SECRET: full database access!)" if role == "service_role" else "")))

    where = args.dir or "git repository"
    if problems:
        print(f"FAIL: {len(problems)} possible secret(s) in {where}:")
        for f, what in problems:
            print(f"  - {f}: {what}")
            if os.environ.get("GITHUB_ACTIONS"):
                print(f"::error file={f}::{what}")
        print("\nIf a real secret was committed: rotate it first (it is public now), then remove it.")
        sys.exit(1)
    print(f"OK: no secrets found in {where} ({len(paths)} files scanned).")


if __name__ == "__main__":
    main()
