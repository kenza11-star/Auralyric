#!/usr/bin/env bash
# Verifies a built release APK is signed by exactly one certificate, and that it is the pinned release certificate.
# Prints only public data. Needs ANDROID_HOME with build-tools (apksigner).
# Usage: verify-apk.sh path/to/release.apk
set -euo pipefail

fail() {
  echo "::error::$1" >&2
  exit 1
}

APK="${1:?usage: verify-apk.sh <apk>}"
PINNED_FILE="${PINNED_FILE:-signing/release-certificate-sha256.txt}"
[ -s "$APK" ] || fail "APK not found: $APK"

if [ -n "${APKSIGNER:-}" ]; then
  apksigner="$APKSIGNER"
else
  : "${ANDROID_HOME:?ANDROID_HOME is not set, so apksigner cannot be found}"
  build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
  [ -n "$build_tools" ] || fail "no Android build-tools under $ANDROID_HOME"
  apksigner="$build_tools/apksigner"
fi

report="$("$apksigner" verify --print-certs "$APK")" || fail "apksigner rejects $APK. It is unsigned or damaged."
signers="$(printf '%s\n' "$report" | grep -c 'certificate SHA-256 digest' || true)"
[ "$signers" = 1 ] || fail "expected exactly one signing certificate, found $signers"

fingerprint="$(printf '%s\n' "$report" | awk '/certificate SHA-256 digest/ { print tolower($NF); exit }')"
pinned="$(tr -d ' \r\n\t' < "$PINNED_FILE" 2>/dev/null || true)"
if [ -z "$pinned" ] || [ "$pinned" = "UNSET" ]; then
  fail "$PINNED_FILE is UNSET, so the APK certificate ($fingerprint) can't be checked"
fi
[ "$fingerprint" = "$pinned" ] || fail "APK signed by $fingerprint, expected the pinned release certificate $pinned"

echo "APK signed by the pinned release certificate: $fingerprint"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "- release certificate SHA-256: \`$fingerprint\` (matches the pinned value)" >> "$GITHUB_STEP_SUMMARY"
fi
