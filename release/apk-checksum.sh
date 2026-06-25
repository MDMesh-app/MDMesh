#!/usr/bin/env sh
# Prints PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM for a signed APK:
# base64url(no-padding) of the SHA-256 of the signing certificate.
set -eu
APK="${1:?usage: apk-checksum.sh <signed.apk>}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner 2>/dev/null || true)}"
[ -n "$APKSIGNER" ] || APKSIGNER=$(ls "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/apksigner 2>/dev/null | sort | tail -1)
HEX=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | awk -F': ' '/SHA-256 digest/{print $2; exit}' | tr -d ' :')
[ -n "$HEX" ] || { echo "no signing cert found in $APK" >&2; exit 1; }
python3 -c "import sys,base64;print(base64.urlsafe_b64encode(bytes.fromhex(sys.argv[1])).decode().rstrip('='))" "$HEX"
