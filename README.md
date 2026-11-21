# Nyandex

Yandex Music patches for [Morphe](https://morphe.software): lyrics from LRCLIB, a Material You
theme, hideable bottom-bar tabs, an in-app settings page, and privacy fixes.

Nyandex is an independent project. It is compatible with Morphe but not made by or affiliated
with the Morphe project, and it is not affiliated with Yandex.

## How to use

Add this repository to Morphe Manager as a patch source:

<a href="https://morphe.software/add-source?github=Ser1-1y/nyandex">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

or add `https://github.com/Ser1-1y/nyandex` manually. Then patch Yandex Music in Morphe Manager.

- **Always patch the original APK/APKM** of a supported version (see below), never an app that
  is already patched.
- Keep **Spoof app signature** enabled (it is on by default). Without it Yandex ID login fails
  with "application signature mismatch".
- After patching, Nyandex options are in the app: **Settings → Nyandex → Nyandex Settings**.

### Supported versions

| App | Versions |
|---|---|
| Yandex Music (`ru.yandex.music`) | `2026.11.1 #171gpr`, `2026.11.2 #172.1gpr` |

The patches locate their targets by code structure, but they are only tested on these versions.
Morphe Manager compares the full version name, including the build suffix.

## Patches

<!-- PATCHES_START EXPANDED -->
<!-- The list below is regenerated on every release by .github/scripts/generate_patches_readme.py. -->

| Patch | Default | What it does |
|---|---|---|
| **Lyrics fallback (LRCLIB)** | on | Accounts without Plus get a one-line notice («Подключите подписку на Плюс…») instead of lyrics. The patch replaces that notice with lyrics from [LRCLIB](https://lrclib.net): synced (karaoke) when available, plain otherwise. Real Yandex lyrics and errors are not touched. |
| **Nyandex settings** | on | Adds a "Nyandex Settings" page, opened from the top of the app's settings. |
| **Hide bottom bar tabs** | on | Switches on the Nyandex Settings page to hide My Vibe, Main, Podcasts, Concerts, For kids or Collection. At least one tab always stays. Requires Nyandex settings. |
| **Monet theme** | off | Uses your wallpaper colors (Material You, Android 12+) for accents, buttons, cards and the My Vibe buttons. Page backgrounds stay black, the mini player keeps its cover color, and the Light / Dark / System setting keeps working. |
| **Spoof app signature** | on | Lets the re-signed app see its original Yandex signature, so Yandex ID login works. |
| **Spoof Firebase certificate header** | on | Sends the original certificate fingerprint to Firebase Installations, so Firebase keeps working after re-signing. |
| **Isolate Passport account** | on | Gives the app its own Android account type, so it can log in next to other Yandex apps signed by Yandex. |
| **Block Plus analytics** | on | Sends Plus event and acquisition traffic to `127.0.0.1`. The music and payment APIs are untouched. |
| **Disable telemetry** | on | Neutralises AppsFlyer, AppMetrica, OpenTelemetry, Firebase Crashlytics and advertising-ID endpoints. |
| **Remove tracking headers** | on | Stops sending `X-Request-Id` and the Yandex device-ID headers. |
| **Disable Play Billing** | on | Disables Play Billing and Play Core services. In-app purchases are not possible. |

<!-- PATCHES_END -->

## Notes

- **Lyrics** come from LRCLIB, a free community database; they are fetched only when Yandex sends
  its Plus notice, with the track's artist, title and duration (nothing else). LRCLIB lyrics are
  community-contributed and may be copyrighted; the fallback is meant for personal use. Tracks
  missing on LRCLIB keep the Yandex notice.
- **Monet** is applied through Android 12+ dynamic colors. On older Android versions the app keeps
  its stock colors.
- **Hidden tabs** disappear after restarting the app; the settings page offers a restart.

## Building

Requirements: JDK 17 or newer and the Android SDK (`ANDROID_HOME`).

```bash
./gradlew :patches:buildAndroid
```

The bundle is written to `patches/build/libs/patches-<version>.mpp`. Load it in Morphe Manager as
a local patch source.

The Morphe Gradle plugin and libraries are published on GitHub Packages, which needs a GitHub
token with `read:packages` (`gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`, or
`GITHUB_ACTOR` / `GITHUB_TOKEN`). Alternatively, point `MORPHE_SOURCE_DIR` at a directory that
contains local checkouts of `morphe-patches-gradle-plugin`, `morphe-patcher` and
`morphe-patches-library`, and the build uses those instead.

### Tests

```bash
YM_APK=/path/to/stock/base.apk ./gradlew :patches:test --rerun
```

The integration tests apply every patch to a stock APK, write the patched APK, and inspect the
resulting bytecode, manifest and resources. Without `YM_APK` they are skipped; the LRCLIB unit
tests always run. Run once per supported version.

## Releases

Releases are created by GitHub Actions with semantic-release from
[Conventional Commits](https://www.conventionalcommits.org) on `main` (`dev` publishes
pre-releases). `patches-bundle.json` points Morphe Manager at the latest release.

## License

GPLv3, see [LICENSE](LICENSE). Built from the
[Morphe patches template](https://github.com/MorpheApp/morphe-patches-template); see
[NOTICE](NOTICE) for the Morphe project's name restriction. "Spoof app signature" uses the
signature spoof of the Morphe patches library.
