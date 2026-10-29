#!/usr/bin/env bash
# Security checks on a BUILT app (the file students install).
#   security/check_apk.sh path/to/WellShare.apk
# Needs ANDROID_HOME (aapt2, apksigner) and python3. Used by the "Android app" workflow.
set -uo pipefail
APK="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"
BT="$(ls -d "${ANDROID_HOME:?ANDROID_HOME not set}"/build-tools/* | sort -V | tail -1)"
AAPT2="$BT/aapt2"; APKSIGNER="$BT/apksigner"
FAIL=0
ok()   { echo "  ok    $1"; }
bad()  { echo "  FAIL  $1"; echo "::error title=APK security check::$1"; FAIL=1; }
warn() { echo "  warn  $1"; echo "::warning title=APK security check::$1"; }

echo "APK: $APK ($(du -h "$APK" | cut -f1))"

# [AND-02] not debuggable
if "$AAPT2" dump badging "$APK" | grep -q "application-debuggable"; then bad "[AND-02] App is debuggable"; else ok "[AND-02] App is not debuggable"; fi

# [AND-01][AND-03] manifest flags as compiled into the APK
XML="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")"
flag() { # $1 attribute name, $2 expected true|false
  local line; line="$(echo "$XML" | grep -E ":$1\(0x[0-9a-f]+\)=" | head -1)"
  if [ "$2" = false ]; then echo "$line" | grep -Eq "=(false|\(type 0x12\)0x0)$"; else echo "$line" | grep -Eq "=(true|\(type 0x12\)0xffffffff)$"; fi
}
flag allowBackup false && ok "[AND-01] Backups disabled" || bad "[AND-01] allowBackup is not false"
flag usesCleartextTraffic false && ok "[AND-03] Plain HTTP disabled" || bad "[AND-03] usesCleartextTraffic is not false"
echo "$XML" | grep -q "networkSecurityConfig" && ok "[AND-03] Network security config present" || bad "[AND-03] networkSecurityConfig missing"

# [AND-05] permissions = reviewed list
ACTUAL="$("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u)"
EXPECTED="$(sort -u "$HERE/expected-permissions.txt")"
if [ "$ACTUAL" = "$EXPECTED" ]; then ok "[AND-05] Permissions match the reviewed list"; else
  bad "[AND-05] Permissions differ from security/expected-permissions.txt"; diff <(echo "$EXPECTED") <(echo "$ACTUAL") || true; fi

# [REL-01] signature
SIG="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)"
echo "$SIG" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" && ok "[REL-01] Signed with APK Signature Scheme v2" || bad "[REL-01] Not signed with v2 scheme"
echo "$SIG" | grep -i "certificate SHA-256 digest" | head -1 | sed 's/^/        /'
if echo "$SIG" | grep -q "CN=Android Debug"; then warn "[REL-02] Signed with a temporary debug key: set up the permanent signing key before giving the app to students"; else ok "[REL-02] Signed with the permanent release key"; fi

# Unpack for content checks
TMP="$(mktemp -d)"; unzip -qq -o "$APK" -d "$TMP"

# [ENC-04] code is obfuscated: internal class names must not survive R8
if python3 - "$TMP" <<'PY'
import glob, sys
data = b"".join(open(p, "rb").read() for p in glob.glob(sys.argv[1] + "/classes*.dex"))
names = [b"Lorg/wellshare/app/SiteMath;", b"Lorg/wellshare/app/UsageMath;", b"Lorg/wellshare/app/Secure;"]
left = [n.decode() for n in names if n in data]
print("        internal class names still readable:", left or "none")
sys.exit(1 if left else 0)
PY
then ok "[ENC-04] Code is shrunk and obfuscated (R8)"; else bad "[ENC-04] Code is not obfuscated"; fi

# [KEY-01] no secrets inside the app (the public anon/publishable key is expected)
if python3 "$HERE/scan_secrets.py" --dir "$TMP" --built; then ok "[KEY-01] No secret keys inside the app"; else bad "[KEY-01] Secret found inside the app"; fi
rm -rf "$TMP"

if [ "$FAIL" -ne 0 ]; then echo "APK security checks FAILED"; exit 1; fi
echo "All APK security checks passed"
