# qt-otp-mobile

[![Android](https://github.com/uberlinuxguy/qt-otp-mobile/actions/workflows/android.yml/badge.svg)](https://github.com/uberlinuxguy/qt-otp-mobile/actions/workflows/android.yml)

An Android authenticator that reads and writes the same encrypted vault as
[qt-otp](https://github.com/uberlinuxguy/qt-otp) on the desktop. Import your
`vault.otpv`, unlock it with the same password, and get the same codes.

Compatibility is not approximate: the app was developed against a vault file
written by qt-otp's own Python code, and the desktop implementation was run
against vaults written by this app. Both directions are covered by tests.

## What it does

- **Import a vault** — pick a `vault.otpv` from anywhere the system file picker
  can reach (Nextcloud, Drive, Downloads, USB storage). The file is validated
  before it replaces anything, and the original is left untouched.
- **Show live codes** — TOTP with SHA1/SHA256/SHA512, 6/7/8 digits, and any
  period, each with a countdown ring. Tap to copy.
- **Add, edit, delete and reorder entries** — the vault is re-encrypted in the
  same v1 format, so the desktop app keeps opening it.
- **Scan QR codes** — `otpauth://` codes via the camera, recognised on-device.
- **Export an encrypted copy** — for backup, or to carry changes back to the
  desktop.
- **Biometric unlock** — optional; see [Security](#security) for how the key is
  handled.
- **Auto-lock** — on leaving the foreground, immediately or after a delay. The
  system file picker is treated as part of the app rather than as leaving it, so
  an import or export does not lock the vault out from under you (bounded: a
  picker left open for over two minutes locks anyway).
- **Hide codes until tapped** and **block screenshots** — both optional.

## The vault format

A vault is a single-line JSON envelope holding an scrypt-derived,
AES-256-GCM-sealed payload:

```json
{
  "magic": "qt-otp-vault",
  "version": 1,
  "cipher": "AES-256-GCM",
  "kdf": {"name":"scrypt","n":32768,"r":8,"p":1,"dklen":32,"salt":"<b64>"},
  "nonce": "<b64>",
  "ciphertext": "<b64>"
}
```

Everything except `ciphertext` is the GCM additional authenticated data, so the
KDF parameters cannot be weakened by editing the file. **The AAD is not the
header bytes as they appear on disk.** It is a re-serialization of the parsed
header with sorted keys and no whitespace, matching Python's
`json.dumps(sort_keys=True, separators=(",", ":"))`:

```
{"cipher":"AES-256-GCM","kdf":{"dklen":32,"n":32768,"name":"scrypt","p":1,"r":8,"salt":"…"},"magic":"qt-otp-vault","nonce":"…","version":1}
```

Keys sort at both levels — `cipher, kdf, magic, nonce, version`, and inside
`kdf`: `dklen, n, name, p, r, salt`. Getting this string wrong is the one
mistake that makes a real vault refuse to open while every self-test still
passes, which is why
[`VaultInteropTest`](app/src/test/java/com/qtotp/mobile/core/VaultInteropTest.kt)
asserts it byte for byte against a vault the desktop app wrote.

The decrypted payload is:

```json
{"payload_version":1,"updated_at":1700000000,"entries":[
  {"id":"…","issuer":"…","account":"…","secret":"…","digits":6,
   "period":30,"algorithm":"SHA1","notes":"…","created_at":1700000000}
]}
```

Nothing is stored in the clear — not even issuer names.

## Security

- **Key derivation** is scrypt at the desktop app's parameters (N=32768, r=8,
  p=1, 32-byte key), implemented in [`Scrypt.kt`](app/src/main/java/com/qtotp/mobile/core/Scrypt.kt)
  rather than pulled from BouncyCastle: one KDF at one parameter set is a small
  enough surface to read end to end, and it keeps a large provider out of the
  APK. It is verified against keys derived by Python's `hashlib.scrypt`.
- **The vault lives in app-private storage** and is excluded from cloud backup
  and device transfer ([`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml)),
  so it leaves the device only when you export it.
- **Biometric unlock seals the vault key, not your password.** A hardware-backed
  AES key in the Android Keystore, marked `setUserAuthenticationRequired(true)`,
  wraps the 32-byte vault key; the wrapped copy is only openable inside a
  successful `BiometricPrompt`. It is invalidated when biometric enrollment
  changes, and the now-useless wrapper is discarded on the next unlock attempt;
  it is also dropped when the password changes or a different vault is imported.
  In every case you fall back to the password. All three paths are covered under
  [Verified on a device](#verified-on-a-device).
- **Locking wipes key material** from memory, and every mutation is written
  through to disk immediately, so locking can never lose data.
- Codes copied to the clipboard are flagged sensitive so they stay out of
  clipboard previews.

Note that a rooted device, or one where the screen lock is compromised, defeats
all of this; the vault is only as private as the device holding it.

## Syncing with the desktop

There is no live sync. The app keeps its own copy in private storage, so the
workflow is explicit:

- Desktop changed? **Settings → Replace with a file** and pick the vault again.
- Phone changed? **Settings → Export a copy**, then point the desktop app at it.

Editing the same vault in both places between syncs will lose whichever set of
changes you import over. If you keep the vault in a synced folder, treat the
desktop as the source of truth and use the phone read-mostly.

## Building

Needs a JDK 17+ and the Android SDK with platform 37. Android Studio's bundled
JDK works; point `JAVA_HOME` at it and `sdk.dir` in `local.properties` at your
SDK.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # unit tests, including interop
./gradlew :app:assembleRelease        # release build (unsigned)
```

Built and verified with Gradle 9.7.1, AGP 9.4.0, Kotlin 2.2.10 (AGP 9's built-in
Kotlin — there is no `kotlin-android` plugin), compileSdk 37, minSdk 26.

[CI](.github/workflows/android.yml) runs the unit tests and builds the debug APK
on every push and pull request to `main`. The APK and the test reports are
attached to each run as artifacts, so a build can be installed on a device
without a local toolchain.

The release variant is signed only if a keystore is supplied through the
environment; without one it still builds, unsigned:

```bash
export KEYSTORE_PATH=~/qt-otp-release.keystore
export KEYSTORE_PASSWORD=...        # PKCS12: one password for store and key
export KEY_ALIAS=qt-otp
./gradlew :app:assembleRelease
```

Pushing a `v*` tag runs [the release
workflow](.github/workflows/release.yml), which signs the APK with the keystore
held in repository secrets and attaches it to a GitHub Release. The debug
signing key is the committed `app/debug.keystore` — the standard, non-secret
debug credentials — so debug builds from CI and from a local checkout share one
identity and install over each other.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

- [`VaultInteropTest`](app/src/test/java/com/qtotp/mobile/core/VaultInteropTest.kt)
  works against [`vault.otpv`](app/src/test/resources/vault.otpv), a real vault
  written by qt-otp, and `expected.json`, the values qt-otp computed for it. It
  covers the canonical AAD, the derived key, entry decoding (including non-ASCII
  issuers and multiline notes), every code at fixed timestamps, tamper
  resistance (a downgraded scrypt cost and a flipped ciphertext byte must both
  fail), and the shape of what this code writes.
- [`TotpTest`](app/src/test/java/com/qtotp/mobile/core/TotpTest.kt) checks the
  RFC 6238 published vectors for all three hash algorithms, so correctness is
  anchored to the spec and not only to agreement with the desktop app, plus
  base32 and `otpauth://` edge cases.

To confirm the desktop app can open what this one writes:

```bash
pip install cryptography
git clone https://github.com/uberlinuxguy/qt-otp /tmp/qt-otp
./gradlew :app:testDebugUnitTest              # writes app/build/interop/*.otpv
python tools/verify_interop.py --qt-otp /tmp/qt-otp
```

That unlocks each generated vault with qt-otp's own `Vault` class, prints the
codes it computes, and saves and reopens the file through the desktop
implementation.

## Verified on a device

Driven end to end with the debug build on a **Pixel 10 Pro XL (Android 17, API
37)** and on an Android 17 emulator (API 37, 16 KB page size):

- A vault written by qt-otp's Python was dropped into app storage; the app
  detected it, unlocked it with the desktop password, and listed all four
  entries — including the 8-digit SHA256, the 7-digit SHA512 on a 60-second
  period, and the `Zürich Bahn ☕` issuer with its multiline notes.
- **Import through the system file picker**: a vault staged in Downloads was
  picked via "Choose a vault file", landed in app-private storage as mode 600,
  left the source file untouched, and unlocked to correct codes.
- **Export through the save dialog**: wrote a copy that is byte-identical to the
  original and that the desktop implementation opens.
- The codes on screen were compared against qt-otp's Python at the same instant
  and matched exactly (`230 869`, `1867 2792`, `194 5376`).
- A reorder done in the UI re-encrypted and rewrote the vault on the device;
  that file was pulled off and opened by the desktop implementation, which then
  saved and reopened it itself.
- Auto-lock fires on backgrounding, a wrong password reports "Wrong password",
  and with "Block screenshots" on, `screencap` returns a frame that is 100% pure
  black over the app area.
- **QR scanning under R8**: the signed release APK decoded an `otpauth://`
  code on the device, so ML Kit's bundled model loads and runs inside the
  minified build. Note that a QR rendered with antialiased edges — an SVG
  scaled by a fractional factor, for instance — may not be decodable by any
  reader even though it looks correct; test with a pixel-exact image.
- **Biometric unlock**: enabling it sealed the vault key into the Keystore —
  what lands in preferences is 48 bytes of ciphertext plus a 12-byte IV, no
  password. Locking and reopening then unwrapped it behind a real fingerprint
  match (`isStrongBiometric=true`) and AES-GCM authenticated, which is itself
  proof the recovered key was the right one. Codes after a biometric unlock
  matched Python exactly.
- **Biometric invalidation**, all three ways the sealed key must be abandoned:
  changing the password clears it, importing a different vault clears it, and
  enrolling a new fingerprint (which permanently invalidates the Keystore key)
  is detected on the next launch — the dead wrapper is dropped, the biometric
  button disappears, and the password still works.
- **Creating a vault from scratch** on the device, then opening it on the
  desktop with the password typed on the phone.
- **Adding an entry by hand**: a secret typed as `jbswy3dp ehpk3pxp` was
  normalized to `JBSWY3DPEHPK3PXP`, saved as 8-digit SHA512.
- **Editing** an entry (fields pre-populated, changes persisted, entry id
  unchanged — a real edit, not a delete-and-recreate) and **deleting** one,
  including choosing "Keep" in the confirmation and having nothing happen.
- **Changing the password**: a wrong current password leaves the vault byte-for-
  byte openable by the old one; the correct one rewrites it under a fresh salt,
  rejects the old password, and preserves entries.
- **Hide codes until tapped**: rows mask to bullets, one tap reveals, and the
  reveal does not survive a lock.
- **Clipboard copy**: Android logged `Clipboard overlay suppressed`, confirming
  the clip is accepted and that the sensitive flag is honoured.
- **QR scanning** (phone only): an `otpauth://` code with a percent-encoded
  account and a `+`-encoded issuer was scanned off a monitor, parsed into an
  8-digit SHA256 entry, saved, and read back by the desktop implementation with
  every field intact.
- Codes were compared against the **phone's own clock** rather than the PC's
  (they agreed to within 1 second), since TOTP depends on absolute time.

## Layout

```
app/src/main/java/com/qtotp/mobile/
  core/       vault format and TOTP, pure JVM and unit-tested
    VaultCrypto.kt   envelope, canonical AAD, AES-256-GCM
    Scrypt.kt        RFC 7914 scrypt
    Pbkdf2.kt        PBKDF2-HMAC-SHA256 over exact UTF-8 password bytes
    VaultPayload.kt  the decrypted JSON payload
    OtpEntry.kt      one entry, with the desktop app's validation rules
    Totp.kt          RFC 4226/6238 and base32
    OtpAuthUri.kt    otpauth:// parsing and rendering
  data/       storage, settings, Keystore key wrapping
  ui/         Compose screens and the view model
tools/verify_interop.py   opens app-written vaults with the desktop code
```

## Known gaps

- **HOTP (counter-based) is not supported**, matching the desktop app, which
  refuses non-`totp` URIs.
- **No live sync** with a synced folder; import and export are manual, as above.
- **No drag-to-reorder** — reordering is via each entry's menu.
- **The release variant is only partly exercised on a device.** The signed,
  R8-minified APK was run on the Pixel 10 Pro XL: a vault was created and
  unlocked, and an `otpauth://` QR was scanned, which covers the two
  `proguard-rules.pro` rules most likely to be wrong — kotlinx.serialization's
  generated serializers and ML Kit's reflective model loading. Biometric
  unlock, import and export have been driven only in the debug build, so the
  Keystore and file-picker paths under R8 are still untested.
- All automated tests cover `core/` only. `data/` and `ui/` — the store, the
  session, settings, Keystore wrapping and every screen — have no automated
  tests; their only coverage is the manual device runs above, so a refactor
  there would not be caught by `./gradlew test`. Every user-facing flow has now
  been driven by hand at least once, but none of it is reproducible in CI.
- Only Android 17 (API 37) has been exercised, despite `minSdk 26`. Nothing is
  known about behaviour on older releases.
- Tested against small vaults (up to five entries). No large-vault or
  long-running behaviour has been measured.

## Licence

The desktop qt-otp is Apache 2.0. This port carries the same licence so the two
stay compatible.
