# Stage 1 audit (2026-11-07)

## Workspace

The workspace was empty apart from a code-review-graph index. There was **no existing Auralynx source,
no upstream code and no tests** to preserve. "Preserve working functionality" was therefore applied to
public upstream *knowledge*: LibrePods, CAPod and OpenPods were cloned to a scratch directory and
read as references (all GPL-3.0). No code was copied; see NOTICE.

## Toolchain (macOS 26 / Apple M2, 8 GB RAM)

| Tool | Found | Used |
|---|---|---|
| JDK | 17.0.19 (Homebrew), 27.0.1 default `JAVA_HOME`, 8 | 17 |
| Gradle | 9.8.2 system | Wrapper 9.8.2 (checksum-pinned) |
| Android SDK | platforms 30–36.1, build-tools up to 37.0.0, NDK 27/28, emulator + 8 AVDs (API 28–36) | Installed platform 37.0, sources 37.0/37.2 |
| Kotlin CLI | present | Kotlin via Gradle plugin 2.4.21 |
| GitHub CLI | authenticated | Read-only checks (repo-name availability, action versions) |
| Physical devices | none attached | — |

## Dependency assessment

Latest stable versions checked against Google Maven / Maven Central: AGP 9.5.1, Kotlin 2.4.21,
KSP 2.3.13, Hilt 2.60.1, Compose BOM 2026.09.00 (Material 3 1.4.0, UI 1.12.1), Room 2.8.5,
Navigation 2.10.2, Lifecycle 2.11.0, DataStore 1.3.0, Glance 1.2.0, coroutines 1.11.0,
serialization 1.12.0. Proprietary SDKs (Play services location/activity, ML Kit) were **rejected**
to keep the app GPL-clean and private; framework equivalents are used.

## Licensing assessment

- Reference projects: GPL-3.0 → Auralynx is GPL-3.0-or-later to stay compatible with the ecosystem
  and allow future code exchange.
- Runtime dependencies: 165 Apache-2.0 + 2 BSD-3-Clause, all GPL-3.0-compatible (scripts/check_licenses.py).

## Name check

- GitHub: ~9 unrelated repositories named "Auralynx" (IoT, transcription, music generators); none in
  the audio-accessory space. `auralynx-android` is free under the current account.
- Google Play: `com.auralynx` is **taken** ("Auralynx: Voice & Pulse"). Chosen application ID:
  `io.github.codewithlakxsh.auralynx` (unused on Play at time of check).
- Trademarks: a web search found no "Auralynx" mark, only similar marks (AURALINK, AURALIS, AURALSYNC).
  **This is not legal clearance.** The existing Play app with the same name is a branding risk to
  review before store publication.

## Build assessment

AGP 9 built-in Kotlin, compileSdk 37, targetSdk 36 (deliberate: API 37 behaviour changes not yet
evaluated), minSdk 26. Release builds use R8 with resource shrinking.

## Feature capability matrix (at audit time)

| Feature | Feasible rootless? | Basis |
|---|---|---|
| Battery/in-ear/lid/model | Yes, via BLE proximity advertisements | CAPod, OpenPods |
| Noise control, CA, press events, rename, 1 % battery | Needs AAP over classic L2CAP | LibrePods |
| AAP channel via public API | **No** (found during Stage 7; see research/ROOTLESS_COMPATIBILITY.md) | SDK sources 37.0/37.2 |
| Loud-sound reduction, hearing features | Needs VendorID → root | LibrePods |
| System-wide EQ | No; only published audio sessions | Android audio effect model |
| System-wide spatial audio | No; system-controlled | Spatializer API |
| Apple Watch pairing | No | Activation requires iPhone + Apple servers |
| Find My network | No | Not exposed to non-Apple platforms |
