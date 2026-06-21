# Publishing OpenTV to Google Play

This is the end-to-end checklist for shipping a paid, open-source build to the
Play Store. Items marked ✅ are already handled in this repo; ⬜ are one-time
actions you do yourself (mostly in the Play Console, which can't live in git).

## In the repository — done

- ✅ Unique application id (`com.buco7854.opentv`) and `targetSdk = 35`.
- ✅ Release build with R8 minify + resource shrinking (`assembleRelease`
  verified; ~3 MB APK).
- ✅ Release signing wired from a keystore supplied via env/Gradle properties,
  with a debug-key fallback so contributors can still build release variants.
- ✅ Tag-driven CI (`.github/workflows/release.yml`) that builds a **signed
  `.aab`** (Play upload format) and `.apk`, and attaches them to a GitHub
  Release.
- ✅ `allowBackup=false`, minimal permissions, adaptive + monochrome icon,
  Android TV banner & leanback entry.
- ✅ `PRIVACY.md` (you'll host this URL — see below) and `LICENSE` (GPL-3.0).

## One-time setup you perform

### 1. Create an upload keystore
```bash
keytool -genkey -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias opentv
```
Keep this file and its passwords safe and **out of git** (already covered by
`.gitignore` patterns for `*.jks`/`*.keystore`). Losing it means you can't
update the app (unless you use Play App Signing, recommended — see step 4).

### 2. Add CI secrets (GitHub → Settings → Secrets → Actions)
- `KEYSTORE_BASE64` = `base64 -w0 upload-keystore.jks`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

To build locally instead, pass the same values as Gradle properties or env:
```bash
KEYSTORE_FILE=/abs/upload-keystore.jks KEYSTORE_PASSWORD=… \
KEY_ALIAS=opentv KEY_PASSWORD=… ./gradlew bundleRelease
```

### 3. Cut a release
```bash
git tag v0.1.0 && git push origin v0.1.0
```
CI produces `app-release.aab` — upload that to Play.

### 4. Google Play Console
- ⬜ Pay the one-time $25 developer registration.
- ⬜ Create the app; enable **Play App Signing** (Google holds the signing key,
  your keystore is just the upload key).
- ⬜ Set it as a **paid app** and configure price (Monetization → set up; needs a
  payments profile). Note: a paid app can be GPL-licensed — buyers may
  redistribute, which is allowed and normal for paid FOSS.
- ⬜ **Store listing:** title, short & full description, the 512×512 icon, a
  1024×500 feature graphic (you can render `docs/banner.svg`), and **real phone
  screenshots** (Play requires actual captures — see below).
- ⬜ **Privacy policy URL:** host `PRIVACY.md` (e.g. GitHub Pages or the raw
  file) and paste the link.
- ⬜ **Data safety form:** declare "no data collected / no data shared" (true —
  the app has no backend or analytics).
- ⬜ **Content rating** questionnaire.
- ⬜ Pick countries, then roll out to internal testing → production.

### 5. Real screenshots for the listing
Play wants genuine captures, not mockups. On a device/emulator:
```bash
adb exec-out screencap -p > screenshot.png
```
Capture Home, a poster grid, a detail page, and the player. The SVGs in
`docs/` are for the README and the feature graphic, not the screenshot slots.

## Notes on "open source + paid"
GPL-3.0 lets you sell binaries while keeping the source public; purchasers
receive the same freedoms (run, study, modify, redistribute). That's compatible
with a paid Play listing — you're charging for the convenience of the built,
auto-updating app, not restricting the code.
