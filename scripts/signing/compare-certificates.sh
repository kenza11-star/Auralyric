#!/usr/bin/env bash
# READ-ONLY comparison: is the key in the existing GitHub secrets the key that signed the installed Vantra APK?
#
# Needs only the keystore and its store password. The alias and key password are OPTIONAL: if the alias is
# unknown, every private-key entry in the keystore is listed with its certificate fingerprint and compared.
#
# It never writes, replaces or deletes a secret, never creates a key, and prints only public data
# (aliases, certificate SHA-256 fingerprints, a verdict). Passwords go through the environment, never a command line.
#
# Installed certificate (one of):
#   INSTALLED_SHA256   64 hex characters, colons/spaces/case ignored
#   INSTALLED_APK      path to the installed APK (read with apk-cert-fingerprint.py)
# Keystore (one of):
#   RELEASE_KEYSTORE_BASE64   the secret's value (CI)
#   RELEASE_KEYSTORE_FILE     path to a .p12/.jks/.keystore (local)
# plus RELEASE_STORE_PASSWORD (required), RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD (optional).
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
clean() { printf '%s' "$1" | tr -cd 'A-Za-z0-9._@ -' | cut -c1-64; }

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

# ---- 2. what we have ----
missing=""
[ -n "${RELEASE_STORE_PASSWORD:-}" ] || missing="$missing RELEASE_STORE_PASSWORD"
if [ -z "${RELEASE_KEYSTORE_FILE:-}" ] && [ -z "${RELEASE_KEYSTORE_BASE64:-}" ]; then
  missing="$missing RELEASE_KEYSTORE_BASE64"
fi
if [ -n "$missing" ]; then
  say "- required secrets not set:$missing"
  verdict 11 "SECRETS_MISSING" "The keystore and its store password are the minimum needed to inspect the key. Nothing was changed. If you never stored them, the installed app was signed by a key that isn't available to CI (see SIGNING.md section 2). Do not create a key until you've decided on the migration."
fi
[ -n "${RELEASE_KEY_ALIAS:-}" ] || say "- RELEASE_KEY_ALIAS not set: reading the alias from the keystore instead"
[ -n "${RELEASE_KEY_PASSWORD:-}" ] || say "- RELEASE_KEY_PASSWORD not set: the key password is not needed to read the certificate"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
KS="${RELEASE_KEYSTORE_FILE:-$work/release.keystore}"
if [ -z "${RELEASE_KEYSTORE_FILE:-}" ]; then
  printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$KS" 2>/dev/null || true
fi
export VANTRA_KS_PASS="$RELEASE_STORE_PASSWORD"

unusable() { say "- keystore problem: $1"; verdict 12 "KEYSTORE_UNUSABLE" "The stored keystore can't be read: $1. Nothing was changed or replaced. Check the secret values before any migration (SIGNING.md section 3 needs your approval)."; }

[ -s "$KS" ] || unusable "the RELEASE_KEYSTORE_BASE64 secret is empty or isn't valid base64"

# ---- 3. open it (PKCS12 first, then legacy JKS) and list entries ----
listing=""
storetype=""
for t in PKCS12 JKS; do
  if listing="$(keytool -J-Duser.language=en -list -v -keystore "$KS" -storetype "$t" -storepass:env VANTRA_KS_PASS 2>/dev/null)"; then
    storetype="$t"; break
  fi
done
[ -n "$storetype" ] || unusable "it won't open with RELEASE_STORE_PASSWORD as a PKCS12 or JKS keystore (wrong store password, or the file is damaged)"
say "- keystore type: $storetype. It opens with the store password."

# lines: alias<TAB>entrytype<TAB>sha256
entries="$(printf '%s\n' "$listing" | awk '
  /^Alias name:/ { alias=$0; sub(/^Alias name: */, "", alias); type=""; next }
  /^Entry type:/ { type=$0; sub(/^Entry type: */, "", type); next }
  /^[ \t]*SHA256:/ { f=$0; sub(/.*SHA256: */, "", f); gsub(/[: ]/, "", f); if (alias != "") { print alias "\t" type "\t" tolower(f); alias="" } }
')"
keys="$(printf '%s\n' "$entries" | awk -F'\t' 'tolower($2) ~ /privatekeyentry/')"
[ -n "$keys" ] || unusable "it opens, but holds no private-key entry (only certificates), so it can't sign anything"

say ""
say "Private-key entries found:"
matched_alias=""
while IFS=$'\t' read -r alias _ fp; do
  [[ "$fp" =~ ^[0-9a-f]{64}$ ]] || continue
  mark=""
  if [ "$fp" = "$installed" ]; then mark="  <- same as the installed app"; matched_alias="$alias"; fi
  say "- alias \`$(clean "$alias")\`: \`$fp\`$mark"
done <<< "$keys"

# ---- 4. can the private key be unlocked? (only reported, never printed) ----
say ""
keypw_note=""
if [ "$storetype" = PKCS12 ]; then
  keyfile="$work/k.pem"
  if openssl pkcs12 -in "$KS" -nocerts -nodes -passin env:VANTRA_KS_PASS -out "$keyfile" >/dev/null 2>&1 && grep -q "PRIVATE KEY" "$keyfile"; then
    keypw_note="verified: the private key decrypts with the store password. In a PKCS12 keystore the key password is the store password, so RELEASE_KEY_PASSWORD must be set to the same value as RELEASE_STORE_PASSWORD."
  else
    keypw_note="NOT verified: the certificate was read, but the private key did not decrypt with the store password. Its key password may be different, which cannot be checked without it."
  fi
  rm -f "$keyfile"
else
  keypw_note="NOT verified: this is a legacy JKS keystore. Its private key may use a password different from the store password, and the key password is not available here, so the private key could not be unlocked. Only the public certificate was compared."
fi
say "- private key password: $keypw_note"
if [ -n "${RELEASE_KEY_PASSWORD:-}" ] && [ "$storetype" = PKCS12 ] && [ "$RELEASE_KEY_PASSWORD" != "$RELEASE_STORE_PASSWORD" ]; then
  say "- note: RELEASE_KEY_PASSWORD differs from RELEASE_STORE_PASSWORD. For a PKCS12 keystore the release build will reject that."
fi
if [ -n "${RELEASE_KEY_ALIAS:-}" ]; then
  if printf '%s\n' "$keys" | awk -F'\t' -v a="$RELEASE_KEY_ALIAS" 'tolower($1)==tolower(a) {f=1} END {exit !f}'; then
    say "- RELEASE_KEY_ALIAS exists in the keystore."
  else
    say "- note: RELEASE_KEY_ALIAS does not name any entry in the keystore."
  fi
fi

# ---- 5. verdict ----
if [ -n "$matched_alias" ]; then
  verdict 0 "MATCH" "The stored keystore holds the key that signed the installed app, under alias \`$(clean "$matched_alias")\`. The certificate comparison is exact. Next: put the fingerprint into \`signing/release-certificate-sha256.txt\`. To build releases, set the secret RELEASE_KEY_ALIAS to \`$(clean "$matched_alias")\`; RELEASE_KEY_PASSWORD per the private-key line above. This check changed nothing."
fi
verdict 10 "MISMATCH" "None of the private-key entries in the stored keystore has the installed app's certificate, so a build signed with this keystore can't update that install in place. Nothing was changed. Options are in SIGNING.md section 6; creating a new key is a one-time migration that needs your approval first."
