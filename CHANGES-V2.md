# Vantra V2 (partial): signing fix and Developer Playground fix

Status: Phase 1 (signing/update safety) and Phase 3 (GitHub rate limits) implemented.
Not started: Phase 1 backup/restore, Phase 2 Terminal redesign. Not compiled (no Android SDK in the build environment).

## Root cause: why updates needed an uninstall
Android only updates an installed app in place when the new APK has the same applicationId AND
is signed with the same certificate. The CI workflow generated a fresh `~/.android/debug.keystore`
on every run, and release fell back to that key whenever `RELEASE_KEYSTORE_BASE64` was missing.
So each build could be signed by a different key, and Android treated it as a different app.

## Changes
- `.github/workflows/build.yml`
  - Restore step fails the run (not just warns) when `RELEASE_KEYSTORE_BASE64` or `RELEASE_STORE_PASSWORD`
    is missing on any non-PR run. Previously the check only ran for one hard-coded repo name.
  - New step prints the release APK's signing certificate SHA-256 to the job summary, so it can be
    compared with the installed app (non-blocking).
- `app/build.gradle.kts`
  - On CI, a release build throws if the release signing config is unavailable, instead of silently
    signing with the debug key. PR builds are exempt (fork PRs never receive secrets).
- `ui/screens/settings/devspace/DevSpaceData.kt` (rewritten, same public entry points)
  - Counts are nullable. A failed request no longer shows as 0 ("No open issues. Nice." was a
    failed request). An unavailable issue list is distinct from an empty one.
  - Every GitHub response is cached on disk with its ETag. Repeat requests send `If-None-Match`;
    a 304 returns the cached body and does not use rate-limit quota.
  - 403/429 are read as rate limits (Retry-After / X-RateLimit-Reset), not generic errors. While the
    limit is active, no network request is sent; the error shows minutes until retry.
  - Saved numbers load on app start, so the last good statistics show offline or while limited.
- `DevRepoCards.kt`: shows "Couldn't load the issue list" + Retry when the issue list is unavailable.
- `DevPlaygroundScreen.kt`, `DeveloperSpaceScreen.kt`: attach the disk cache; the Developer Space
  screen no longer forces a fresh fetch on every open (it used to spend ~7 requests per visit).

## Before the next release: one-time setup (required)
1. Confirm the release keystore exists as GitHub secrets: `RELEASE_KEYSTORE_BASE64`,
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
   If the keystore was never stored, create one once (`keytool -genkeypair ...`), store it as a secret,
   and back it up offline. Losing it means no future update can ever install over the app.
2. Check which key the installed app uses:
       adb shell pm path com.vantra.music         # note the base.apk path
       adb pull <that path> installed.apk
       apksigner verify --print-certs installed.apk   # SHA-256 digest
   Compare with the SHA-256 printed in the next CI run's summary.
   - Match: in-place updates work from now on.
   - No match: the installed app was signed by a key we don't have. No new build can install over it
     without uninstalling. Recovery: export data first (see below), uninstall once, install the new
     build. After that, every future release updates in place.
3. versionCode is still 73. Each release must be higher than the installed versionCode, or Android
   refuses the install as a downgrade. Bump it per release.

## Known gaps
- Backup/restore (SAF export/import) is not implemented yet. Before any uninstall, copy the data
  manually or wait for that feature.
- `workflows/build.yml` at the repo root is a stray copy; only `.github/workflows/build.yml` runs.
- The Developer Space countdown is computed when the error occurs and does not tick live.
- Not verified by compilation or on a device. Run:
      ./gradlew :app:compileArm64GmsReleaseKotlin   (needs Android SDK)
      ./gradlew :app:testFossDebugUnitTest
- The nested folder `kenza11-star-Auralyric-b7c4b7c/` duplicates the project; it was left untouched.

---

## Phase 1, part 2: backup and restore (this release, versionCode 74)

Format is still `vantra-transfer` version 1. New manifest fields are optional, so older zips still import and
older app versions ignore the new fields.

### Protecting data on export
- **Login and API secrets are never written to a zip.** The exclusion list gained the proxy password,
  OpenRouter and DeepL API keys, and the Monochrome Turnstile JWT and its expiry. A scan of all 373 preference
  keys confirms every credential-like key is excluded.
- **YouTube library feedback tokens are cleared** from the exported library (`song.libraryAddToken`,
  `song.libraryRemoveToken`). The export fails rather than ship them if the copy can't be cleared.
- Older zips that still contain those secrets have them dropped on import.

### Confirming a replace
- Picking a zip now shows a confirmation with what the zip holds (songs, playlists, listening events, streak
  state, recovery chances used this week) next to what the phone holds now. Previously the zip's contents were
  never shown before the library was replaced.

### Restoring safely
- **Rollback journal.** Every path the import replaces is moved aside (rename, atomic on the same filesystem)
  and recorded with whether it existed before. If the import fails, the originals are put back and new files
  are removed. Settings and preferences are kept as copies, written atomically.
- **Interrupted imports recover.** If the app is killed mid-import, the next start replays the journal before
  anything else loads. A committed import is never rolled back.
- **Failures are reported.** After a rollback the app restarts so in-memory settings reload, and shows a
  message saying the data is as it was.
- Previously a failed import deleted its staging folder and left partially replaced data behind.

### Verified
- `sh tests/transfer-journal/run.sh` compiles the real `Journal` class from `TransferRestore.kt` with kotlinc 2.1
  and runs 16 scenarios: replace and commit, failure mid-import, double undo, recovery after a simulated kill,
  in-place edit rollback, and removal of files the import created. **All pass.**
- Every edited Android file parses cleanly. A planted syntax error was detected by the same parser, so the
  clean result is meaningful. Full type-checking needs the Android SDK.
- Error profile of the 10 edited files compared with the original zip. Remaining errors are unresolved Android,
  Compose and kotlinx references, which this sandbox can't resolve. The one new function (`currentCounts`) follows
  the same pattern as existing suspend calls in the same files.

### Not verified (needs a device or Gradle)
- Full `./gradlew assembleRelease`: the Android SDK and Google Maven are not reachable from this environment.
- The end-to-end export → import → restart cycle on a phone, including streak values after restore.
- The StreakManager and Room behaviour after restore.
- Whether the installed app's signing certificate matches the release keystore. There's no installed APK in
  the sandbox. On a device run:
      adb shell pm path com.vantra.music
      adb pull <path> installed.apk
      apksigner verify --print-certs installed.apk
  Compare the SHA-256 with the fingerprint in the next CI run's summary. Do not generate a new key and assume
  it matches.

### Still open
- Phase 2 (Terminal redesign) is not started in this release. See the notes in the final reply.
- A legacy `app/src/main/kotlin/com/mochyy/` tree is in the zip. CI removes it, but local builds that don't
  run that step would compile it. Confirm before deleting.

---

## Permanent release signing (versionCode 74 base + CI run number)

Read `SIGNING.md` first. It covers the migration check, key creation, the four secrets, backup, and verification.

### What changed
- **Random keys removed from CI.** The "Generate debug.keystore" step is gone. Release never falls back to a debug
  key. Without the permanent key a release is left unsigned, which can't be installed as a release.
- **One key, pinned.** `signing/release-certificate-sha256.txt` holds the expected certificate fingerprint. It starts
  as `UNSET`, so no release builds until it's set. The keystore must match it, and so must the built APK.
- **Restore and verify.** `scripts/signing/restore-release-keystore.sh` decodes the secret and runs
  `verify-release-keystore.sh`: the keystore opens with the store password, the alias exists, the private key
  decrypts with the key password, and the certificate equals the pinned fingerprint.
- **APK verified before upload.** `scripts/signing/verify-apk.sh` runs `apksigner`, requires exactly one signer, and
  compares it to the pinned fingerprint. The artifact is uploaded only after that passes.
- **Strict secrets.** Empty values count as missing. All four secrets are required on any non-PR run.
  `RELEASE_KEY_PASSWORD` must equal `RELEASE_STORE_PASSWORD`, because PKCS12 uses one password.
- **Keystore removed from the runner** at the end of every job (`if: always()`).
- **Keystore can't be committed.** `.gitignore` covers `app/keystore/`, `*.keystore`, `*.p12`, `*.jks`, `*.pfx`,
  `release-signing/`, and `signing/*.secret`. Verified with `git check-ignore` in a scratch repository.
- **Monotonic versionCode.** `versionCode = signing/version-code-base.txt + GITHUB_RUN_NUMBER`. CI refuses to build
  without a run number, rather than guessing one.
- **Unused `persistentDebug` config removed** (it had a hard-coded password).
- **Output hygiene.** The `::add-mask::` loop was removed because it echoed secrets into the log. GitHub already
  masks values from `secrets.*`.

### Verified (actual results)
- `bash tests/signing/run.sh`: **25 of 25 pass.** Uses a throwaway keystore in a temp folder. Covers creation,
  mode 600, refusal to overwrite, the correct secrets passing, wrong store password, wrong alias, differing key
  password, `UNSET` pin (and the message shows the fingerprint to pin), wrong pinned certificate, corrupted
  keystore, the base64 round trip (byte-identical), invalid base64, a missing secret on push, a PR without
  secrets (unsigned), APK signed by another certificate, two signers, a rejected APK, and no password in any output.
- `sh tests/transfer-journal/run.sh`: **all 16 rollback scenarios pass** (unchanged code, rerun).
- Gradle script parses. Its parse-error counts match the original file exactly (47 / 8 / 47), so the edits add no syntax errors.
- Workflow YAML parses. All scripts pass `bash -n`.
- `git check-ignore` confirms the keystore paths are ignored and ordinary files are tracked.
- Streak keys (all six) are carried into backups. Playlists and listening history are in the library database, which is exported.

### Not verified here (needs your machine or CI)
- **`apksigner` output.** The APK verifier was tested against a stub that prints the documented line format. The
  real tool wasn't available. The first CI run must confirm the real output parses.
- **The full Gradle build.** There's no Android SDK here. The versionCode arithmetic and the signing config run
  only in CI.
- **GitHub secrets were not set.** I have no access to your repository's secrets from this environment. Section 4
  of `SIGNING.md` gives the website and CLI routes. I didn't touch the existing secrets.
- **Whether the installed app matches any key.** There's no installed APK here. Follow `SIGNING.md` section 2.
  No new key can update an APK signed with a different key. Section 6 is the migration if it doesn't match.

### Not done in this release
- **Phase 2 (Terminal redesign) was not started.** This release is signing, so it's the only work done here.
- The legacy `app/src/main/kotlin/com/mochyy/` tree is still in the zip. CI deletes it, but a local build that
  skips that step would compile it. Confirm before deleting it.


---

## Signing check: read-only certificate comparison (this session)

- `scripts/signing/apk-cert-fingerprint.py`: stdlib-only reader for an APK's signing certificate (v2/v3 block, v1 fallback).
- `scripts/signing/compare-certificates.sh`: read-only comparison of installed certificate vs the stored keystore
  (verdicts MATCH / MISMATCH / SECRETS_MISSING / KEYSTORE_UNUSABLE). Writes nothing, prints no secret.
- `.github/workflows/verify-signing.yml`: manual-only, `contents: read`, no build, no upload.
- `signing/installed-certificate-sha256.txt`: public fingerprint read from the supplied APK.
- `tests/signing/run.sh`: 41 checks, 41 passing in the build sandbox (throwaway keystore only). New: `tests/signing/make_fake_apk.py`.
- No existing change was altered. `signing/release-certificate-sha256.txt` is still `UNSET` on purpose until the verdict is known.
- Not run: the workflow on GitHub (needs your secrets), Android compilation (no Android SDK here).
