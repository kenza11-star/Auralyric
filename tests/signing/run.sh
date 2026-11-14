#!/usr/bin/env bash
# Tests the signing scripts with a THROWAWAY keystore in a temp folder. Nothing touches the real release key.
# Run from the project root: bash tests/signing/run.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
S="$ROOT/scripts/signing"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
LOG="$WORK/all-output.log"
: > "$LOG"
pass=0; failed=0

check() { # name expected_exit actual_exit
  if [ "$2" = "$3" ]; then echo "PASS $1"; pass=$((pass+1))
  else echo "FAIL $1 (expected exit $2, got $3)"; failed=$((failed+1)); fi
}
run() { # runs a command, captures everything for the leak check, returns its exit code
  "$@" >>"$LOG" 2>&1; echo $?
}

# T1: create a keystore in a temp folder (outside the repo)
export VANTRA_SIGNING_DIR="$WORK/signing"
create_out="$(bash "$S/create-release-keystore.sh" 2>&1)"; c=$?
echo "$create_out" >> "$LOG"
check "create keystore" 0 "$c"
KS="$WORK/signing/vantra-release.p12"
PASS="$(cat "$WORK/signing/github-secrets/RELEASE_STORE_PASSWORD")"
FP="$(cat "$WORK/signing/certificate-sha256.txt")"
[ -s "$KS" ] && check "keystore file written" 0 0 || check "keystore file written" 0 1
perm="$(stat -c %a "$KS")"; check "keystore is mode 600" 0 "$([ "$perm" = 600 ] && echo 0 || echo 1)"
# the printed summary must never contain the password
echo "$create_out" | grep -q "$PASS" && check "create output has no password" 0 1 || check "create output has no password" 0 0

# T2: correct secrets + pinned fingerprint passes
export RELEASE_KEYSTORE_FILE="$KS" RELEASE_STORE_PASSWORD="$PASS" RELEASE_KEY_ALIAS="vantra-release" RELEASE_KEY_PASSWORD="$PASS"
export PINNED_FILE="$WORK/pinned.txt"; echo "$FP" > "$PINNED_FILE"
check "verify: good secrets and pinned fingerprint" 0 "$(run bash "$S/verify-release-keystore.sh")"

# T3: wrong store password
check "verify: wrong store password fails" 1 "$(RELEASE_STORE_PASSWORD=wrong RELEASE_KEY_PASSWORD=wrong run bash "$S/verify-release-keystore.sh")"

# T4: wrong alias
check "verify: wrong alias fails" 1 "$(RELEASE_KEY_ALIAS=nope run bash "$S/verify-release-keystore.sh")"

# T5: key password differs from store password
check "verify: differing key password fails" 1 "$(RELEASE_KEY_PASSWORD=other-$PASS run bash "$S/verify-release-keystore.sh")"

# T6: pinned file still UNSET (the migration gate)
echo UNSET > "$WORK/unset.txt"
out6="$(PINNED_FILE="$WORK/unset.txt" bash "$S/verify-release-keystore.sh" 2>&1)"; c6=$?
echo "$out6" >> "$LOG"
check "verify: UNSET pin fails" 1 "$c6"
echo "$out6" | grep -q "$FP" && check "UNSET message shows the fingerprint to pin" 0 0 || check "UNSET message shows the fingerprint to pin" 0 1

# T7: pinned fingerprint belongs to some other certificate
echo "$(printf 'f%.0s' {1..64})" > "$WORK/other.txt"
check "verify: wrong pinned certificate fails" 1 "$(PINNED_FILE="$WORK/other.txt" run bash "$S/verify-release-keystore.sh")"

# T8: corrupted keystore bytes
head -c 300 "$KS" > "$WORK/corrupt.p12"
check "verify: corrupted keystore fails" 1 "$(RELEASE_KEYSTORE_FILE="$WORK/corrupt.p12" run bash "$S/verify-release-keystore.sh")"

# T9: CI restore path, base64 secret round trip, then verify
base64 -w0 < "$KS" > "$WORK/b64.txt"
GHO="$WORK/gh-output"; : > "$GHO"
c9="$(RELEASE_KEYSTORE_BASE64="$(cat "$WORK/b64.txt")" RELEASE_STORE_PASSWORD="$PASS" RELEASE_KEY_ALIAS=vantra-release RELEASE_KEY_PASSWORD="$PASS" \
  RELEASE_KEYSTORE_FILE="$WORK/ci/app/keystore/release.keystore" GITHUB_OUTPUT="$GHO" EVENT_NAME=push PINNED_FILE="$PINNED_FILE" run bash "$S/restore-release-keystore.sh")"
check "restore: valid secrets verify and sign" 0 "$c9"
cmp -s "$KS" "$WORK/ci/app/keystore/release.keystore" && check "restore: decoded keystore is byte-identical" 0 0 || check "restore: decoded keystore is byte-identical" 0 1
grep -q "signed=true" "$GHO" && check "restore: reports signed=true" 0 0 || check "restore: reports signed=true" 0 1

# T10: invalid base64
check "restore: invalid base64 fails" 1 "$(RELEASE_KEYSTORE_BASE64='@@not base64@@' RELEASE_STORE_PASSWORD="$PASS" RELEASE_KEY_ALIAS=x RELEASE_KEY_PASSWORD="$PASS" RELEASE_KEYSTORE_FILE="$WORK/ci2/k" GITHUB_OUTPUT=/dev/null EVENT_NAME=push run bash "$S/restore-release-keystore.sh")"

# T11: one secret missing on a non-PR run
check "restore: missing secret on push fails" 1 "$(RELEASE_KEYSTORE_BASE64="$(cat "$WORK/b64.txt")" RELEASE_STORE_PASSWORD="$PASS" RELEASE_KEY_ALIAS= RELEASE_KEY_PASSWORD="$PASS" RELEASE_KEYSTORE_FILE="$WORK/ci3/k" GITHUB_OUTPUT=/dev/null EVENT_NAME=push run bash "$S/restore-release-keystore.sh")"

# T12: pull request without secrets is allowed, unsigned
GHO2="$WORK/gh-output-2"; : > "$GHO2"
check "restore: pull request without secrets builds unsigned" 0 "$(RELEASE_KEYSTORE_BASE64= RELEASE_STORE_PASSWORD= RELEASE_KEY_ALIAS= RELEASE_KEY_PASSWORD= RELEASE_KEYSTORE_FILE="$WORK/ci4/k" GITHUB_OUTPUT="$GHO2" EVENT_NAME=pull_request run bash "$S/restore-release-keystore.sh")"
grep -q "signed=false" "$GHO2" && check "pull request reports signed=false" 0 0 || check "pull request reports signed=false" 0 1

# T13: create refuses to overwrite
check "create: refuses to overwrite an existing keystore" 1 "$(VANTRA_SIGNING_DIR="$WORK/signing" run bash "$S/create-release-keystore.sh")"

# T15-T18: APK verification logic, against a STUB apksigner that prints the documented report line.
# This tests our parsing and comparison only. The real apksigner output still needs one device/CI check.
STUB="$WORK/apksigner-stub"
cat > "$STUB" <<'STUBSH'
#!/usr/bin/env bash
# stub: prints what apksigner verify --print-certs prints for the signers listed in $STUB_SIGNERS
echo "Verifies"
i=0
for fp in $STUB_SIGNERS; do i=$((i+1)); echo "Signer #$i certificate DN: CN=stub"; echo "Signer #$i certificate SHA-256 digest: $fp"; done
exit ${STUB_EXIT:-0}
STUBSH
chmod +x "$STUB"
APK="$WORK/app-release.apk"; echo "not really an apk" > "$APK"
UPPER_FP="$(echo "$FP" | tr 'a-f' 'A-F')"
export APKSIGNER="$STUB" PINNED_FILE="$WORK/pinned.txt"
check "apk: single signer matching the pin passes" 0 "$(STUB_SIGNERS="$UPPER_FP" run bash "$S/verify-apk.sh" "$APK")"
check "apk: different signer fails" 1 "$(STUB_SIGNERS="$(printf 'e%.0s' {1..64})" run bash "$S/verify-apk.sh" "$APK")"
check "apk: two signers fails" 1 "$(STUB_SIGNERS="$FP $FP" run bash "$S/verify-apk.sh" "$APK")"
check "apk: apksigner rejecting the file fails" 1 "$(STUB_SIGNERS="$FP" STUB_EXIT=1 run bash "$S/verify-apk.sh" "$APK")"
unset APKSIGNER


# T19-T27: read-only certificate comparison (compare-certificates.sh) and the stdlib APK certificate reader
unset RELEASE_KEYSTORE_FILE
CMP="$S/compare-certificates.sh"
cmp_run() { GITHUB_STEP_SUMMARY="$WORK/summary.md" "$@" >>"$LOG" 2>&1; echo $?; }
B64="$(cat "$WORK/b64.txt")"
export RELEASE_STORE_PASSWORD="$PASS" RELEASE_KEY_ALIAS=vantra-release RELEASE_KEY_PASSWORD="$PASS"
check "compare: same key is MATCH (exit 0)" 0 "$(INSTALLED_SHA256="$UPPER_FP" RELEASE_KEYSTORE_BASE64="$B64" cmp_run bash "$CMP")"
check "compare: colon-separated fingerprint accepted" 0 "$(INSTALLED_SHA256="$(echo "$UPPER_FP" | sed 's/../&:/g; s/:$//')" RELEASE_KEYSTORE_BASE64="$B64" cmp_run bash "$CMP")"
check "compare: other certificate is MISMATCH (exit 10)" 10 "$(INSTALLED_SHA256="$(printf 'a%.0s' {1..64})" RELEASE_KEYSTORE_BASE64="$B64" cmp_run bash "$CMP")"
check "compare: no secrets is SECRETS_MISSING (exit 11)" 11 "$(INSTALLED_SHA256="$FP" RELEASE_KEYSTORE_BASE64= RELEASE_STORE_PASSWORD= RELEASE_KEY_ALIAS= RELEASE_KEY_PASSWORD= cmp_run bash "$CMP")"
check "compare: wrong password is KEYSTORE_UNUSABLE (exit 12)" 12 "$(INSTALLED_SHA256="$FP" RELEASE_KEYSTORE_BASE64="$B64" RELEASE_STORE_PASSWORD=wrong RELEASE_KEY_PASSWORD=wrong cmp_run bash "$CMP")"
check "compare: wrong alias is KEYSTORE_UNUSABLE (exit 12)" 12 "$(INSTALLED_SHA256="$FP" RELEASE_KEYSTORE_BASE64="$B64" RELEASE_KEY_ALIAS=nope cmp_run bash "$CMP")"
check "compare: garbage base64 is KEYSTORE_UNUSABLE (exit 12)" 12 "$(INSTALLED_SHA256="$FP" RELEASE_KEYSTORE_BASE64='@@@' cmp_run bash "$CMP")"
check "compare: malformed installed fingerprint is a usage error (exit 1)" 1 "$(INSTALLED_SHA256=xyz RELEASE_KEYSTORE_BASE64="$B64" cmp_run bash "$CMP")"
# read-only: the keystore file and the pinned file are untouched
before="$(sha256sum "$KS" "$PINNED_FILE" | sha256sum)"
INSTALLED_SHA256="$FP" RELEASE_KEYSTORE_FILE="$KS" bash "$CMP" >>"$LOG" 2>&1
check "compare: leaves keystore and pinned file unchanged" "$before" "$(sha256sum "$KS" "$PINNED_FILE" | sha256sum)"
grep -q "MATCH" "$WORK/summary.md" && check "compare: summary written" 0 0 || check "compare: summary written" 0 1

# APK certificate reader: build a minimal APK with a v2 signing block carrying the throwaway certificate
openssl pkcs12 -in "$KS" -nokeys -clcerts -passin env:RELEASE_STORE_PASSWORD 2>/dev/null | openssl x509 -outform DER -out "$WORK/c.der" 2>/dev/null
python3 "$ROOT/tests/signing/make_fake_apk.py" "$WORK/c.der" "$WORK/fake.apk" "$WORK/fake-two.apk" "$WORK/fake-unsigned.apk"
check "apk reader: v2 certificate equals keystore fingerprint" "$FP" "$(python3 "$S/apk-cert-fingerprint.py" "$WORK/fake.apk" 2>/dev/null)"
check "apk reader: two signers are both reported" 2 "$(python3 "$S/apk-cert-fingerprint.py" "$WORK/fake-two.apk" 2>/dev/null | wc -l)"
check "apk reader: unsigned APK fails" 1 "$(run python3 "$S/apk-cert-fingerprint.py" "$WORK/fake-unsigned.apk")"
check "apk reader: not an APK fails" 1 "$(run python3 "$S/apk-cert-fingerprint.py" "$WORK/b64.txt")"
check "compare: APK input matches its key (exit 0)" 0 "$(INSTALLED_APK="$WORK/fake.apk" RELEASE_KEYSTORE_BASE64="$B64" cmp_run bash "$CMP")"
check "compare: two-signer APK is refused (exit 1)" 1 "$(INSTALLED_APK="$WORK/fake-two.apk" RELEASE_KEYSTORE_BASE64="$B64" cmp_run bash "$CMP")"

# T14: no password appears in any captured output
leaks=0
for secret in "$PASS"; do grep -q -- "$secret" "$LOG" && leaks=$((leaks+1)); done
check "no password in any script output" 0 "$leaks"

echo
echo "passed: $pass   failed: $failed"
[ "$failed" = 0 ]
