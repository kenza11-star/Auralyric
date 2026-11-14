#!/usr/bin/env bash
# Checks the release keystore and its secrets before anything is built with them.
# Used by CI and by people setting up. It never prints a password, and it only prints public data (the certificate fingerprint).
#
# Inputs (environment):
#   RELEASE_KEYSTORE_FILE  path to the PKCS12 keystore      (default app/keystore/release.keystore)
#   RELEASE_STORE_PASSWORD keystore password
#   RELEASE_KEY_ALIAS      alias of the release key
#   RELEASE_KEY_PASSWORD   key password (must equal the store password, see below)
#   PINNED_FILE            file holding the expected SHA-256 certificate fingerprint (default signing/release-certificate-sha256.txt)
set -euo pipefail

fail() {
  echo "::error::$1" >&2
  exit 1
}

KEYSTORE="${RELEASE_KEYSTORE_FILE:-app/keystore/release.keystore}"
PINNED_FILE="${PINNED_FILE:-signing/release-certificate-sha256.txt}"

for name in RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
  [ -n "${!name:-}" ] || fail "$name is missing. Set all four release secrets, see SIGNING.md."
done
[ -s "$KEYSTORE" ] || fail "keystore file is missing or empty: $KEYSTORE"

# A PKCS12 keystore stores the key under the store password, so a different key password cannot work.
# Requiring them to be equal makes that mistake impossible instead of confusing to debug.
[ "$RELEASE_KEY_PASSWORD" = "$RELEASE_STORE_PASSWORD" ] \
  || fail "RELEASE_KEY_PASSWORD must be identical to RELEASE_STORE_PASSWORD for a PKCS12 keystore (see SIGNING.md)."

# passwords go through the environment, so they never appear on a command line or in the process list
export VANTRA_KS_PASS="$RELEASE_STORE_PASSWORD"

# 1. the keystore opens with this password and holds this alias
listing="$(keytool -list -v -keystore "$KEYSTORE" -storetype PKCS12 \
  -storepass:env VANTRA_KS_PASS -alias "$RELEASE_KEY_ALIAS" 2>/dev/null)" \
  || fail "the keystore won't open with these secrets, or the alias doesn't exist in it"

# 2. the store password is right (PKCS12 MAC) and the private key decrypts with the key password
openssl pkcs12 -in "$KEYSTORE" -info -nokeys -passin env:VANTRA_KS_PASS >/dev/null 2>&1 \
  || fail "the store password does not open the keystore (openssl)"
key_out="$(mktemp)"
trap 'rm -f "$key_out"' EXIT
openssl pkcs12 -in "$KEYSTORE" -nocerts -nodes -passin env:VANTRA_KS_PASS -out "$key_out" >/dev/null 2>&1 \
  || fail "the private key does not decrypt with RELEASE_KEY_PASSWORD"
grep -q "PRIVATE KEY" "$key_out" || fail "the keystore contains no private key"

# 3. the certificate is the pinned release certificate
fingerprint="$(printf '%s\n' "$listing" | awk '/SHA256:/ { sub(/.*SHA256: */, ""); gsub(/[: ]/, ""); print tolower($0); exit }')"
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || fail "couldn't read the certificate fingerprint from the keystore"
echo "release certificate SHA-256: $fingerprint"

pinned="$(tr -d ' \r\n\t' < "$PINNED_FILE" 2>/dev/null || true)"
if [ -z "$pinned" ] || [ "$pinned" = "UNSET" ]; then
  fail "$PINNED_FILE is UNSET. For a new key, write the fingerprint above into it and commit. For an existing key, verify it first (SIGNING.md, step 2)."
fi
[ "$pinned" = "$fingerprint" ] \
  || fail "this keystore's certificate ($fingerprint) is not the pinned release certificate ($pinned). Refusing to sign."

echo "keystore OK: opens, alias present, private key decrypts, certificate matches the pinned fingerprint"
