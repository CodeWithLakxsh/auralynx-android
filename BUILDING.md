# Building Auralynx

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 (Temurin recommended). Builds were tested only with JDK 17; newer JDKs are untested. |
| Android SDK Platform | 37.0 (`platforms;android-37.0`) |
| Android SDK Build-Tools | 37.0.0 |
| Gradle | 9.8.2 via the wrapper (checksum-pinned in `gradle/wrapper/gradle-wrapper.properties`) |

Gradle 9.9.0 was released on 2026-11-06, the day before this release; the project stays on the
previous stable 9.8.2 until 9.9 has had time to mature. Key plugin and library versions live in
`gradle/libs.versions.toml` (AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.13, Hilt 2.60.1, Compose BOM 2026.09.00,
Room 2.8.5), all verified as the latest stable releases on 2026-11-07.

```bash
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or set ANDROID_HOME
export JAVA_HOME=/path/to/jdk-17
```

## Common tasks

```bash
./gradlew assembleDebug                 # installable debug APK
./gradlew assembleRelease               # minified release APK (unsigned unless signing is configured)
./gradlew testDebugUnitTest :core:common:test :core:protocol:test :core:automation:test :core:dsp:test
./gradlew :app:lintDebug                # Android lint (aborts on errors)
./gradlew connectedDebugAndroidTest     # instrumentation tests; needs a device or emulator
python3 scripts/check_licenses.py       # dependency license audit
python3 scripts/scan_secrets.py         # secret scan of tracked files
./scripts/package-release.sh            # dist/: APKs, source ZIP, SHA256SUMS
```

## Release signing

Signing credentials are read **only from environment variables**; nothing is stored in the repository.

| Variable | Meaning |
|---|---|
| `AURALYNX_KEYSTORE_PATH` | Path to the keystore file |
| `AURALYNX_KEYSTORE_PASSWORD` | Keystore password |
| `AURALYNX_KEY_ALIAS` | Key alias |
| `AURALYNX_KEY_PASSWORD` | Key password |

If any is missing, `assembleRelease` produces `app-release-unsigned.apk`. In GitHub Actions, set the
repository secrets `AURALYNX_KEYSTORE_BASE64` (base64 of the keystore) plus the three passwords/alias;
the release workflow decodes the keystore to a temporary file and deletes it afterwards.

Create a key (keep it out of the repository):

```bash
keytool -genkeypair -v -keystore auralynx-release.jks -alias auralynx -keyalg RSA -keysize 4096 -validity 10000
base64 -i auralynx-release.jks | pbcopy   # paste into the AURALYNX_KEYSTORE_BASE64 secret
```

## Versioning

`gradle.properties` is the single source of truth:

- `auralynx.versionName`: semantic version with pre-release label (`0.1.0-alpha`). Tags are `v<versionName>`;
  the release workflow refuses mismatched tags.
- `auralynx.versionCode`: `MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + build`, with alpha builds 0–49,
  beta 50–79, release candidates 80–98, final 99. `0.1.0-alpha` = 1000.
- `auralynx.applicationId`: `io.github.codewithlakxsh.auralynx` (debug builds add `.debug`).

## Reproducibility

Builds are expected to be reproducible for the same commit, JDK 17 vendor/version, SDK packages and
Gradle wrapper: the wrapper is checksum-pinned, all dependency versions are fixed in the version catalog,
dependency metadata is not embedded in the APK (`dependenciesInfo` disabled), and the build does not
inject timestamps or VCS data. To verify, build the same tag twice on clean machines and compare the
unsigned release APKs (`apksigner` signatures differ by key; compare `app-release-unsigned.apk`).
Release APKs embed the git commit (`META-INF/version-control-info.textproto`, added by AGP), so
their hash changes with every commit even when app sources do not; compare builds of the **same
commit**. Observed on 2026-11-07 (Apple M2, JDK 17.0.19): two full rebuilds of the same commit with
`--rerun-tasks` were byte-identical. Reproducibility **across machines has not been verified**; see
ROADMAP.md.

## Project layout

See [ARCHITECTURE.md](ARCHITECTURE.md).
