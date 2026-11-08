# Real-device test report

```
PROJECT: AURALYNX (0.1.0-alpha, standard edition)
Test dates: 2026-11-07 – 2026-11-08
```

Status labels: **PASS** verified on physical hardware · **FAIL** tested and failed · **BLOCKED** cannot
run because of a technical restriction · **NOT TESTED** · **EXPERIMENTAL** partial, needs more validation.
Personal identifiers (device names, Bluetooth addresses, serials) are redacted.

## Device information

| | |
|---|---|
| Manufacturer | OnePlus |
| Model | OnePlus Pad 3 (OPD2415), arm64-v8a |
| Android version | 16 (OxygenOS 16.0.7.200, oplusrom V16.1.0, security patch 2026-05-01) |
| API level | 36 |
| Bluetooth | Classic + LE, A2DP (AAC codec in use), HFP; companion-device setup supported |

## AirPods information

| | |
|---|---|
| Model | **AirPods Pro 2 (USB-C)**: BLE model code 0x2420, identified from its advertisement |
| Firmware | Unknown (not readable without Apple's accessory channel; not checked on an Apple device) |
| Bluetooth connection | Already paired with the tablet; A2DP + HFP connected, AAC, audio routed to Bluetooth (`bt_a2dp`) |

## Installation

| | |
|---|---|
| APK installed | PASS: debug APK via `adb install -r` (data preserved across 12 reinstalls) |
| Application launch | PASS: cold start 570–744 ms |
| Startup crashes | None observed (Logcat) |

## Feature test results

| Feature | Result | Evidence |
|---|---|---|
| **Bluetooth: detection** | PASS (after fixes BUG-3, BUG-6) | Accessory shown immediately after launch; model identified from advertisement |
| Bluetooth: connection history | PASS (after fix BUG-7) | Re-discovered connections no longer logged as new connections |
| Bluetooth: disconnect / reconnect / adapter off-on | NOT TESTED | Session ended (USB link lost) before these steps |
| Audio playback routing | PASS | `dumpsys audio`: media track active on the Bluetooth A2DP device |
| Background monitoring | PASS | `connectedDevice` foreground service running after the app was backgrounded; ongoing notification posted |
| **Battery: left / right / case** | PASS | Advertisement values L 100→90 %, R 100→90 %, Case 50 %, each labelled ±10 % |
| Battery: charging status | PASS | Charging shown while the buds were in the case |
| Battery: history | PASS | 18 measured rows persisted, showing real drain while worn |
| Battery: Android comparison | PASS (finding) | Android itself reports **no** battery for these AirPods (`BATTERY=-1`, value null); Auralynx's advertisement data is the only source |
| Battery: accuracy | EXPERIMENTAL | Values plausible and consistent; 10 % resolution; no independent reference available |
| Low-battery alert | NOT TESTED | Buds stayed above the maximum alert threshold (50 %) |
| **ANC** | BLOCKED | Public API rejects classic L2CAP: `invalid socketType - 3` (channel experiment H1 = false) |
| **Transparency** | BLOCKED | Same as ANC |
| **Adaptive Audio** | BLOCKED | Same as ANC |
| **Ear detection: left removal** | PASS (after fix BUG-9) | Advertisement-based; music paused ≈ 1–3 s after removal and resumed on re-insertion |
| Ear detection: both removed | PASS (after fix BUG-9) | Paused ≈ 0.9 s after the first matching advertisement; resumed when both were back |
| Ear detection: right (secondary) removal | EXPERIMENTAL | Works, but took **≈ 1–2 min**: the AirPods' primary (left) bud kept advertising "both in ear" until the update reached it. Accessory-side timing; not fixable app-side without the accessory channel |
| **Conversational Awareness** | BLOCKED | Requires the accessory channel |
| **Gestures** (press events) | BLOCKED | Requires the accessory channel. AirPods' own stem controls (AVRCP) not tested |
| **Equalizer** | EXPERIMENTAL | Presets switch; curve and bands update; settings persist. Scope correct: YouTube does not publish its audio session, so the EQ cannot apply to it. Audible effect on Auralynx's own player not tested (no file loaded) |
| **Smart automation** | PASS (after fixes BUG-12/13/14) | Profile rule applied the "Podcast" audio profile; the listening-mode action was honestly logged once as "failed — not connected" |
| **Translation** | BLOCKED on this tablet | No Android system translation service (`dumpsys translation`: service not found). On-device speech recognition is available. Free cloud translation (MyMemory) was added afterwards but **not yet tested on the tablet** |
| **Recorder** | NOT TESTED | Session ended before this step |
| **Finder** | EXPERIMENTAL | Live RSSI from the AirPods observed in diagnostics (−41 to −83 dBm); finder screen walk test not done |
| **Quick Settings** | NOT TESTED | Tiles not added |
| **Widget** | NOT TESTED | Widget not added |
| **Spatial audio** | NOT TESTED (audio) | Screen works; platform reports Spatializer available, disabled by user, no head tracker. App-local playback not tested |
| Rootless channel experiment | PASS (as a measurement) | `transport_probe: Public API rejects classic L2CAP on API 36: invalid socketType - 3` |
| Tablet layout | PASS (after fix BUG-2) | Content capped to a readable width on the 12" screen |
| Rotation / dark-mode switch | NOT TESTED | |

## Automated tests

| | |
|---|---|
| Unit tests | PASS: 126 tests in 17 suites, 0 failures |
| Instrumentation tests | PASS: 11 on an Android 16 emulator in the previous session; the 2 database tests added in this session were compiled but not run on a device |
| Lint | PASS: 0 errors, 1 warning (targetSdk 36, deliberate) |

## Bugs found

| ID | Severity | Bug | Root cause |
|---|---|---|---|
| BUG-1 | Low | Onboarding promised noise control | Copy written before the API finding |
| BUG-2 | Low | Content stretched edge-to-edge on tablets | No maximum content width |
| BUG-3 | Critical | Connected AirPods not detected (or detected late) | Dynamic receiver registered `NOT_EXPORTED` never receives broadcasts from the Bluetooth stack (UID 1002); connected snapshot taken only once while Bluetooth was off |
| BUG-4 | Medium | "Identified in an earlier session" shown for a name guess | Name-guessed model was persisted and later treated as remembered |
| BUG-5 | Medium | Battery shown as "Available" with no value | Capability counted standard Bluetooth without a reported value |
| BUG-6 | Critical | No battery/ear data from advertisements | Name guess "AirPods Pro (1st gen)" rejected the real AirPods Pro 2 (USB-C) advertisements |
| BUG-7 | Medium | Duplicate "Connected" history entries | Every app start re-recorded an existing connection |
| BUG-8 | Medium | `rssi=127` treated as a strong signal | Android's "no RSSI" sentinel not handled |
| BUG-9 | High | Ear detection did nothing | Auto-pause used only accessory-channel data; background scan was hardware-batched (~30 s flush) |
| BUG-10 | Medium | Genuine frames from the user's AirPods dropped | Fixed −65 dBm threshold applied even to an already matched advertising address |
| BUG-11 | Low | Misleading translation message | "No service" and "pair unsupported" not distinguished |
| BUG-12 | Medium | Same automation failure logged 28 times | Failed actions were not remembered as attempted |
| BUG-13 | Medium | Automation re-evaluated on every advertisement | Trigger included the full accessory state |
| BUG-14 | Low | "Audio profile #7" instead of its name | Description used the database id |

## Bugs fixed

All 14 above, each rebuilt, reinstalled and re-tested on the tablet (BUG-11 wording and BUG-14 names
verified in code and unit tests; BUG-14 history wording not re-captured on device). Regression unit
tests were added for BUG-6/BUG-10 (advertiser trust), BUG-7 and BUG-4 (database), BUG-9 (ear-state
debouncer, built from the stale-frame pattern captured on the tablet).

## Remaining issues

1. Accessory-channel features (ANC/Transparency/Adaptive, Conversational Awareness, press events, 1 %
   battery, instant ear detection) remain **BLOCKED** in this build: Android offers no public API. An
   opt-in non-SDK transport was prototyped but not added; it needs an explicit maintainer decision.
2. Secondary-bud ear detection is slow (≈ 1–2 min) when relying on advertisements.
3. Not yet tested on hardware: disconnect/reconnect, Bluetooth toggle, recorder, finder walk test, QS
   tiles, widget, rotation, low-battery alert, free cloud translation, spatial/EQ audible output.
4. The USB debugging connection dropped during testing; the final build containing free cloud
   translation was not installed on the tablet.

## Final APK location

`dist/` (generated by `scripts/package-release.sh`, not committed):
`auralynx-v0.1.0-alpha-debug.apk` (installable) and `auralynx-v0.1.0-alpha-release-unsigned.apk`.

## SHA-256

See `dist/SHA256SUMS`. Release APK hashes change with every commit because AGP embeds the git revision.

## Overall readiness

**Usable as a rootless AirPods companion on this tablet** (detection, battery, wear status, ear-based
pause for the primary bud and both buds, automation, history), **not ready for release**: accessory
controls are blocked on stock Android, several features remain untested on hardware, and release signing
is not configured. Recommended label: experimental alpha.
