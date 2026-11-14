#!/usr/bin/env bash
# READ-ONLY comparison: is the key in the existing GitHub secrets the key that signed the installed Vantra APK?
#
# It never writes, replaces or deletes a secret, never creates a key, and prints only public data
# (certificate SHA-256 fingerprints and a verdict). Passwords go through the environment, never a command line.
#
# Installed certificate (one of):
#   INSTALLED_SHA256   64 hex characters, colons/spaces/case ignored
#   INSTALLED_APK      path to the installed APK (read with apk-cert-fingerprint.py)
# Keystore (one of):
#   RELEASE_KEYSTORE_BASE64   the secret's value (CI)
#   RELEASE_KEYSTORE_FILE     path to a .p12/.keystore (local)
# plus RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD.
#
# Exit codes: 0 MATCH | 10 MISMATCH | 11 SECRETS_MISSING | 12 KEYSTORE_UNUSABLE | 1 bad input
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

say() { echo "$1"; echo "$1" >> "$SUMMARY"; }
bad() { echo "::error::$1" >&2; exit 1; }
verdict() { # code, name, explanation
  say ""
  say "**verdict: $2**"
  say ""
  say "$3"
  exit "$1"
}
normalize() { printf '%s' "$1" | tr -d ' :\r\n\t' | tr 'A-F' 'a-f'; }

# ---- 1. the installed certificate (public) ----
installed=""
if [ -n "${INSTALLED_SHA256:-}" ]; then
  installed="$(normalize "$INSTALLED_SHA256")"
elif [ -n "${INSTALLED_APK:-}" ]; then
  [ -s "$INSTALLED_APK" ] || bad "INSTALLED_APK not found: $INSTALLED_APK"
  out="$(python3 "$HERE/apk-cert-fingerprint.py" "$INSTALLED_APK" 2>/dev/null)" \
    || bad "couldn't read a signing certificate from $INSTALLED_APK"
  [ "$(printf '%s\n' "$out" | wc -l)" = 1 ] || bad "the installed APK has more than one signer, refusing to guess"
  installed="$(normalize "$out")"
else
  bad "set INSTALLED_SHA256 or INSTALLED_APK"
fi
[[ "$installed" =~ ^[0-9a-f]{64}$ ]] || bad "the installed fingerprint is not 64 hex characters"

say "### Vantra signing check (read-only)"
say ""
say "- installed app certificate SHA-256: \`$installed\`"

# ---- 2. the secrets ----
missing=""
for name in RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  [ -n "${!name:-}" ] || missing="$missing $name"
done
if [ -z "${RELEASE_KEYSTORE_FILE:-}" ] && [ -z "${RELEASE_KEYSTORE_BASE64:-}" ]; then
  missing="$missing RELEASE_KEYSTORE_BASE64"
fi
if [ -n "$missing" ]; then
  say "- secrets not set:$missing"
  verdict 11 "SECRETS_MISSING" "These secrets are empty or don't exist, so the existing key can't be compared. Nothing was changed. If you never stored a key, the installed app was signed by a key that isn't available to CI (see SIGNING.md section 2). Do not create a key until you've decided on the migration."
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
KS="${RELEASE_KEYSTORE_FILE:-$work/release.keystore}"
if [ -z "${RELEASE_KEYSTORE_FILE:-}" ]; then
  printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$KS" 2>/dev/null || true
fi
chmod 600 "$work"/* 2>/dev/null || true
export VANTRA_KS_PASS="$RELEASE_STORE_PASSWORD"

unusable() { say "- keystore problem: $1"; verdict 12 "KEYSTORE_UNUSABLE" "The stored key can't be used as it is: $1. Nothing was changed or replaced. Fix the secret or confirm the key is really lost before any migration (SIGNING.md section 3 needs your approval)."; }

[ -s "$KS" ] || unusable "the keystore secret is empty or isn't valid base64"
listing="$(keytool -list -v -keystore "$KS" -storetype PKCS12 -storepass:env VANTRA_KS_PASS -alias "$RELEASE_KEY_ALIAS" 2>/dev/null)" \
  || unusable "it won't open with RELEASE_STORE_PASSWORD, or the alias RELEASE_KEY_ALIAS isn't in it"
[ "$RELEASE_KEY_PASSWORD" = "$RELEASE_STORE_PASSWORD" ] \
  || unusable "RELEASE_KEY_PASSWORD differs from RELEASE_STORE_PASSWORD (a PKCS12 key needs the same password)"
keyfile="$work/k.pem"
openssl pkcs12 -in "$KS" -nocerts -nodes -passin env:VANTRA_KS_PASS -out "$keyfile" >/dev/null 2>&1 \
  && grep -q "PRIVATE KEY" "$keyfile" || unusable "the private key doesn't decrypt"
rm -f "$keyfile"

secret_fp="$(printf '%s\n' "$listing" | awk '/SHA256:/ { sub(/.*SHA256: */, ""); gsub(/[: ]/, ""); print tolower($0); exit }')"
[[ "$secret_fp" =~ ^[0-9a-f]{64}$ ]] || unusable "its certificate fingerprint couldn't be read"

say "- GitHub secrets certificate SHA-256: \`$secret_fp\`"
say "- the stored key opens, the alias exists and the private key decrypts"

if [ "$secret_fp" = "$installed" ]; then
  verdict 0 "MATCH" "The existing key signed the installed app. Keep the secrets. Put this fingerprint into \`signing/release-certificate-sha256.txt\`, commit, and releases will update in place. No migration, no uninstall."
fi
verdict 10 "MISMATCH" "The existing key is usable but is not the key that signed the installed app, so a build signed with it can't update that install in place. Nothing was changed. Options are in SIGNING.md section 6; creating a new key is a one-time migration that needs your approval first."
