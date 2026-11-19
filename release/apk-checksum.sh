#!/usr/bin/env sh
# Prints PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM for a signed APK:
# base64url(no-padding) of the SHA-256 of the signing certificate.
# With --recorded instead, prints the release certificate's checksum recorded in release/agent-signature-checksum.
set -eu
APK="${1:?usage: apk-checksum.sh <signed.apk> | --recorded}"
if [ "$APK" = --recorded ]; then
  CK=$(grep -v '^#' "$(dirname "$0")/agent-signature-checksum" | grep . || true)
  printf '%s\n' "$CK" | grep -Eqx '[A-Za-z0-9_-]{43}' \
    || { echo "release/agent-signature-checksum must hold one base64url SHA-256 line (got '$CK')" >&2; exit 1; }
  printf '%s\n' "$CK"
  exit 0
fi
# Locate apksigner: an explicit $APKSIGNER, then PATH, then the newest build-tools under any of the
# common SDK roots (GitHub runners set ANDROID_SDK_ROOT, not ANDROID_HOME — the old default missed it).
APKSIGNER="${APKSIGNER:-$(command -v apksigner 2>/dev/null || true)}"
if [ -z "$APKSIGNER" ]; then
  for ROOT in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" /opt/android-sdk /usr/local/lib/android/sdk; do
    [ -n "$ROOT" ] || continue
    C=$(ls "$ROOT"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
    [ -n "$C" ] && { APKSIGNER="$C"; break; }
  done
fi
[ -n "$APKSIGNER" ] || { echo "apksigner not found (set APKSIGNER or ANDROID_SDK_ROOT)" >&2; exit 1; }
HEX=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | awk '/SHA-256 digest/{print $NF; exit}' | tr -d ' :')
[ -n "$HEX" ] || { echo "no signing cert found in $APK" >&2; exit 1; }
python3 -c "import sys,base64;print(base64.urlsafe_b64encode(bytes.fromhex(sys.argv[1])).decode().rstrip('='))" "$HEX"
