# Vantra release signing

Every Vantra release must be signed with the same permanent key. Android treats an APK signed with a different
key as a different app, so an update signed with another key can't install over the installed one, and the user
has to uninstall, which deletes their data.

This document covers the setup, the migration check, backup, and verification. Read sections 1 and 2 before
changing anything.

## 0. What is enforced automatically

| Rule | Where it is enforced |
|---|---|
| A release is signed only with the permanent key. CI never creates a random or debug key. | `scripts/signing/restore-release-keystore.sh`, `app/build.gradle.kts` (no debug fallback for release) |
| All four `RELEASE_*` secrets must be present on any run that isn't a pull request. | `restore-release-keystore.sh` |
| The keystore opens, the alias exists, the private key decrypts, and the certificate equals the pinned fingerprint. | `scripts/signing/verify-release-keystore.sh` |
| The built APK is signed by exactly one certificate, and it's the pinned one. Otherwise nothing is uploaded. | `scripts/signing/verify-apk.sh` |
| The keystore is never committed. | `.gitignore` (`app/keystore/`, `*.keystore`, `*.p12`, `*.jks`) |
| The versionCode only goes up. | `signing/version-code-base.txt` + `GITHUB_RUN_NUMBER` |
| No password is printed. | Passwords are passed through environment variables. `tests/signing/run.sh` checks the output. |

The pinned fingerprint lives in `signing/release-certificate-sha256.txt`. It starts as `UNSET`, so **no release
can build until you set it.** That's intentional.

## 1. Check which key is installed on your phone

On a phone with Vantra installed (USB debugging on, `adb` installed on a computer):

```
adb shell pm path com.vantra.music
adb pull <path printed above, the base.apk> installed.apk
apksigner verify --print-certs installed.apk
```

Note the `Signer #1 certificate SHA-256 digest` value. That's the installed app's certificate. Call it **INSTALLED**.

## 2. Check which key the existing GitHub secrets hold (migration gate)

You can't read the secrets, but CI can. Push a branch with this repository state (the pinned file is still `UNSET`).
The `Restore and verify the permanent release key` step decodes the existing `RELEASE_KEYSTORE_BASE64`, prints its
certificate fingerprint, and then fails with "pinned file is UNSET". That failure is expected. The printed line
`release certificate SHA-256: …` is the fingerprint of the existing keys. Call it **SECRETS**.

Decide:

- **SECRETS equals INSTALLED.** The installed app was signed by the key you already have. Keep the secrets. Write
  SECRETS into `signing/release-certificate-sha256.txt`, commit, and releases update in place. Nothing is deleted.
  Back the key up now (section 5). Stop here.
- **SECRETS does not equal INSTALLED, or there are no secrets.** The key that signed the installed app isn't
  available. No key you create can update that installed app in place. Go to section 3 for the one-time
  migration. Do not claim otherwise to anyone.

Don't overwrite or delete the existing secrets until you've verified which key is which. The creation script
refuses to overwrite them by default.

## 2a. Quickest way: the read-only check workflow (phone only, no adb, no computer)

The installed app's certificate was read from your attached APK (`vantra-arm64-release.zip`) and stored, as public
data, in `signing/installed-certificate-sha256.txt`:

```
cde6689e269798fac35b611f1e9dc4196887e94df4ea5974510cc38f175c9930
```

(self-signed, `CN=Vantra, O=Vantra, C=ID`, issued 2026-11-08.) That is **INSTALLED** if the attached APK is the one on your phone.

To compare it with the key held in your GitHub secrets, from the GitHub website or the GitHub mobile app:

1. Open the repository → **Actions** → **Verify signing key (read-only)** → **Run workflow** → **Run workflow**.
   Leave the box empty.
2. When it finishes (about a minute), open the run → **Summary**. It shows one verdict:

| Verdict | Meaning | What to do |
|---|---|---|
| `MATCH` | The stored key signed the installed app. | Copy the fingerprint into `signing/release-certificate-sha256.txt`, commit. Done. |
| `MISMATCH` | The stored key works but is a different key. | Nothing is changed. Read section 6, then decide. A new key needs your approval. |
| `SECRETS_MISSING` | One or more of the four secrets isn't set. | Nothing is changed. Don't create a key yet. |
| `KEYSTORE_UNUSABLE` | The secrets exist but the key won't open (wrong password, alias, bad base64). | Fix the secret value first. Section 3 only if the key is truly lost. |

This workflow only runs when you press the button. It builds nothing, uploads nothing, and cannot write, replace or
delete a secret (`permissions: contents: read`). Secrets are masked by GitHub and the script prints only fingerprints.

The same check works locally: `INSTALLED_APK=installed.apk RELEASE_KEYSTORE_FILE=my.p12 RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=... RELEASE_KEY_PASSWORD=... bash scripts/signing/compare-certificates.sh`. To read an APK's
certificate without apksigner: `python3 scripts/signing/apk-cert-fingerprint.py installed.apk`. That reads the
public certificate only; it does not verify the signature.

## 3. Create the permanent key (only if section 2 says so)

Run this on your own computer. It doesn't need CI and it never prints a secret.

Requirements: JDK 17 or newer (`keytool`), `openssl`, and `git` to check the folder is outside the repository.

```
sh scripts/signing/create-release-keystore.sh
```

It creates a folder `~/vantra-release-signing/` (or `$VANTRA_SIGNING_DIR`) with:

- `vantra-release.p12` — the keystore. This is the key. Never commit or send it.
- `certificate-sha256.txt` — the public fingerprint. Safe to share; pin it.
- `github-secrets/` — the four secret values, one file each. Move them into a password manager, then delete this folder's copies.

## 4. Configure the four GitHub secrets

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | contents of `github-secrets/RELEASE_KEYSTORE_BASE64` (one line) |
| `RELEASE_STORE_PASSWORD` | contents of `github-secrets/RELEASE_STORE_PASSWORD` |
| `RELEASE_KEY_ALIAS` | `vantra-release` |
| `RELEASE_KEY_PASSWORD` | the same value as `RELEASE_STORE_PASSWORD` (PKCS12 uses one password, and CI refuses a mismatch) |

**Option A, GitHub website (no command line needed):** repository → Settings → Secrets and variables → Actions →
New repository secret. Paste each value. Don't paste into chat, issues, or screenshots.

**Option B, GitHub CLI:** after `gh auth login`:

```
sh scripts/signing/create-release-keystore.sh --set-github-secrets OWNER/REPO
```

This refuses if any of the four secrets already exists. Only add `--replace-existing` after section 2 has shown
that replacing them is what you want.

Then write the fingerprint from `certificate-sha256.txt` into `signing/release-certificate-sha256.txt`, commit
it, and push. The fingerprint is public. The keystore never leaves your machine except as the GitHub secret.

## 5. Back up the key (do this before the first release)

Losing the key means no future update can install over Vantra, so back it up in at least two places:

1. **Keystore file.** Copy `vantra-release.p12` to two encrypted locations you control, such as an encrypted USB
   drive and an encrypted cloud archive. Don't use email, chat, the repository, or an unencrypted drive.
2. **Passwords and alias.** Store the store password, key password (same value), and alias
   `vantra-release` in a password manager entry titled "Vantra release signing key".
3. **Certificate fingerprint.** Store it alongside the keystore. It's how you check a restored copy.
4. **Check the backup.** Copy the file to an empty folder, then run
   `RELEASE_KEYSTORE_FILE=<copy> RELEASE_STORE_PASSWORD=… RELEASE_KEY_ALIAS=vantra-release RELEASE_KEY_PASSWORD=… PINNED_FILE=<file with the fingerprint> sh scripts/signing/verify-release-keystore.sh`.
   It should print "keystore OK".
5. Delete the plaintext copies in `~/vantra-release-signing/` once the password manager and the two file backups
   are confirmed. Don't leave them lying around on the computer.

Only the password manager and the encrypted backups should hold the key. GitHub holds it only as the secret.

## 6. Migrating installed users (section 2 said "no matching key")

No new key can update the installed app. One migration is needed:

1. Build the new release and verify its fingerprint (section 7).
2. Tell users to open Settings → Storage → **Export everything**, and keep the zip.
3. Users uninstall Vantra, install the new release, then open Settings → Storage → **Import everything** and pick the zip.
4. From then on, every release updates in place, because every release uses this key.

Before the first migration release, check that the export → import round trip works on a test phone. Streaks,
recovery chances, playlists, listening history, and settings are in the zip (login tokens and API keys are not).

## 7. Verify a release

After CI passes, the run's summary shows the certificate fingerprint, and it must equal the pinned value. On any
phone or computer:

```
apksigner verify --print-certs vantra-arm64-release.apk
```

Check that the `Signer #1 certificate SHA-256 digest` equals `signing/release-certificate-sha256.txt`, and that
there's only one signer.

## 8. Version codes

`versionCode = signing/version-code-base.txt + GITHUB_RUN_NUMBER`. Each CI run gets a higher number, so an
update always installs over the previous release. A rerun of the same run keeps its number.

Local (non-CI) builds use the base only and are never released.

If the workflow is renamed or recreated, the run number starts again. Then raise `signing/version-code-base.txt`
above the largest versionCode any user has installed, and commit it.

## 9. Recovery and what not to do

- A pull request has no secrets, so it builds an unsigned APK for testing. Unsigned APKs can't be installed as releases.
- If CI fails with "pinned file is UNSET", the fingerprint is printed in that step. Pin it, don't skip the check.
- Never change the pinned fingerprint to make a failing build pass. A failing check means the key or the secrets are wrong.
- Never sign with a debug or generated key, even temporarily. That's the reason this system exists.
- If the keystore is lost and no backup exists, the only option is the migration in section 6, with a new key.
