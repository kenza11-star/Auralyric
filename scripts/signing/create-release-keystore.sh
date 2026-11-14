#!/usr/bin/env bash
# Run this ON YOUR OWN MACHINE, once, to create Vantra's permanent release keystore.
# Nothing secret is printed. Passwords and the keystore are written to a folder outside the repository.
#
#   sh scripts/signing/create-release-keystore.sh
#   sh scripts/signing/create-release-keystore.sh --set-github-secrets OWNER/REPO   (needs the gh CLI, see SIGNING.md)
#
# Refuses to overwrite an existing keystore, and refuses to write inside the repository.
set -euo pipefail

fail() {
  echo "error: $1" >&2
  exit 1
}

ALIAS="vantra-release"
OUT_DIR="${VANTRA_SIGNING_DIR:-$HOME/vantra-release-signing}"
KEYSTORE="$OUT_DIR/vantra-release.p12"
SET_REPO=""
REPLACE_EXISTING=0

while [ $# -gt 0 ]; do
  case "$1" in
    --set-github-secrets) SET_REPO="${2:?--set-github-secrets needs OWNER/REPO}"; shift 2 ;;
    --replace-existing) REPLACE_EXISTING=1; shift ;;
    *) fail "unknown argument: $1" ;;
  esac
done

command -v keytool >/dev/null || fail "keytool not found. Install a JDK 17 or newer."
command -v openssl >/dev/null || fail "openssl not found."

[ -e "$KEYSTORE" ] && fail "a keystore already exists at $KEYSTORE. It is never overwritten. Back it up and move it if you truly need a new one."

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

# refuse to put the key inside a git checkout, where it could be committed
if repo_root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null)"; then
  case "$(cd "$OUT_DIR" && pwd)/" in
    "$repo_root"/*) fail "$OUT_DIR is inside the repository ($repo_root). Pick a folder outside it with VANTRA_SIGNING_DIR." ;;
  esac
fi

umask 077
store_password="$(openssl rand -hex 24)"
export VANTRA_NEW_PASS="$store_password"

# PKCS12 uses one password for the store and the key. The same value is stored as both secrets.
keytool -genkeypair \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -storepass:env VANTRA_NEW_PASS \
  -keypass:env VANTRA_NEW_PASS \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10950 \
  -dname "CN=Vantra Release, O=Vantra, C=ID" >/dev/null

chmod 600 "$KEYSTORE"

# the secret values, one file each. Store them in a password manager, then delete this folder's copies.
secrets_dir="$OUT_DIR/github-secrets"
mkdir -p "$secrets_dir"
chmod 700 "$secrets_dir"
base64 -w0 < "$KEYSTORE" > "$secrets_dir/RELEASE_KEYSTORE_BASE64"
printf '%s' "$store_password" > "$secrets_dir/RELEASE_STORE_PASSWORD"
printf '%s' "$ALIAS" > "$secrets_dir/RELEASE_KEY_ALIAS"
printf '%s' "$store_password" > "$secrets_dir/RELEASE_KEY_PASSWORD"
chmod 600 "$secrets_dir"/*

# verify exactly what CI will verify, before anyone trusts this key
RELEASE_KEYSTORE_FILE="$KEYSTORE" \
RELEASE_STORE_PASSWORD="$store_password" \
RELEASE_KEY_ALIAS="$ALIAS" \
RELEASE_KEY_PASSWORD="$store_password" \
PINNED_FILE=/dev/null \
  bash "$(dirname "$0")/verify-release-keystore.sh" > "$OUT_DIR/verify.log" 2>&1 || true

fingerprint="$(keytool -list -v -keystore "$KEYSTORE" -storetype PKCS12 -storepass:env VANTRA_NEW_PASS -alias "$ALIAS" 2>/dev/null \
  | awk '/SHA256:/ { sub(/.*SHA256: */, ""); gsub(/[: ]/, ""); print tolower($0); exit }')"
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || fail "couldn't read the new certificate's fingerprint"
printf '%s\n' "$fingerprint" > "$OUT_DIR/certificate-sha256.txt"

echo "Created the release keystore in: $OUT_DIR"
echo "  keystore:              vantra-release.p12  (never commit this)"
echo "  certificate SHA-256:   $fingerprint"
echo "  secret values:         $secrets_dir/   (move to a password manager, then delete the files)"
echo
echo "Next steps: see SIGNING.md, sections 3 to 6."

if [ -n "$SET_REPO" ]; then
  command -v gh >/dev/null || fail "gh CLI not found. Set the secrets by hand, see SIGNING.md."
  gh auth status >/dev/null 2>&1 || fail "gh is not logged in. Run: gh auth login"
  existing="$(gh secret list -R "$SET_REPO" --json name -q '.[].name' 2>/dev/null || true)"
  for name in RELEASE_KEYSTORE_BASE64 RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
    if printf '%s\n' "$existing" | grep -qx "$name" && [ "$REPLACE_EXISTING" = 0 ]; then
      fail "$name already exists in $SET_REPO. Nothing was changed. Read SIGNING.md before replacing secrets, then rerun with --replace-existing."
    fi
  done
  for name in RELEASE_KEYSTORE_BASE64 RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
    gh secret set "$name" -R "$SET_REPO" < "$secrets_dir/$name"
    echo "set $name"
  done
fi
