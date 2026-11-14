#!/usr/bin/env bash
# CI step: turns the RELEASE_KEYSTORE_BASE64 secret into the keystore file the build reads, then verifies it.
# Pull requests have no secrets, so for them the APK is built unsigned and nothing is decoded.
# Writes "signed=true|false" to GITHUB_OUTPUT. Never prints a secret.
set -euo pipefail

fail() {
  echo "::error::$1" >&2
  exit 1
}

OUT="${RELEASE_KEYSTORE_FILE:-app/keystore/release.keystore}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_FILE="${GITHUB_OUTPUT:-/dev/null}"

# GitHub masks every value that comes from secrets.*, so nothing here echoes a secret.

present=0
for name in RELEASE_KEYSTORE_BASE64 RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  [ -n "${!name:-}" ] && present=$((present + 1))
done

if [ "$present" = 0 ] && [ "${EVENT_NAME:-}" = "pull_request" ]; then
  echo "no release signing secrets for this pull request: the APK is built unsigned, which is only for testing"
  echo "signed=false" >> "$OUTPUT_FILE"
  exit 0
fi
[ "$present" = 4 ] || fail "all four secrets must be set (RELEASE_KEYSTORE_BASE64, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD). Found $present. Refusing to build a release that can't be signed with the permanent key."

mkdir -p "$(dirname "$OUT")"
printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$OUT" 2>/dev/null || fail "RELEASE_KEYSTORE_BASE64 is not valid base64"
chmod 600 "$OUT"

RELEASE_KEYSTORE_FILE="$OUT" bash "$HERE/verify-release-keystore.sh"
echo "signed=true" >> "$OUTPUT_FILE"
