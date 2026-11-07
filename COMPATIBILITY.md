# Compatibility

Legend: **Operable** = works on stock Android with the listed transport · **Channel** = implemented,
needs the Apple accessory channel (no public Android API yet) · **Root** = needs Apple's Device ID in
the phone's Bluetooth stack · **Research** = experimental only · — = not offered by the model.

"Declared" support comes from public community implementations (LibrePods, CAPod). Auralynx upgrades a
feature to *observed* only after receiving matching data from your accessory.

**Hardware validation: none of the rows below has been verified by the Auralynx project on physical
hardware yet** (v0.1.0-alpha).

## Accessory features by model

| Model (model numbers) | BLE code | Battery (BLE) | In-ear (BLE) | Noise control | Adaptive | Conv. awareness | Press events | Rename | LSR / hearing |
|---|---|---|---|---|---|---|---|---|---|
| AirPods 1st gen (A1523, A1722) | 0x0220 | Operable | Operable | — | — | — | — | Channel | — |
| AirPods 2nd gen (A2031, A2032) | 0x0F20 | Operable | Operable | — | — | — | — | Channel | — |
| AirPods 3rd gen (A2564, A2565) | 0x1320 | Operable | Operable | — | — | — | — | Channel | — |
| AirPods 4 (A3050, A3053, A3054) | 0x1920 | Operable | Operable | — | — | — | — | Channel | — |
| AirPods 4 ANC (A3055–A3057) | 0x1B20 | Operable | Operable | Channel | Channel | Channel | Channel | Channel | — |
| AirPods Pro 1st gen (A2083, A2084) | 0x0E20 | Operable | Operable | Channel (Off/ANC/Transparency) | — | — | — | Channel | — |
| AirPods Pro 2 Lightning (A2698, A2699, A2931) | 0x1420 | Operable | Operable | Channel | Channel | Channel | Channel | Channel | Root |
| AirPods Pro 2 USB-C (A3047–A3049) | 0x2420 | Operable | Operable | Channel | Channel | Channel | Channel | Channel | Root |
| AirPods Pro 3 (A3063–A3065) | 0x2720 | Operable | Operable | Channel | Channel | Channel | Channel | Channel | Root; heart rate: Research |
| AirPods Max Lightning (A2096) | 0x0A20 | Operable | — | Channel (unverified) | — | — | — | Channel | — |
| AirPods Max USB-C (A3184) | 0x1F20 | Operable | — | Channel (unverified) | — | — | — | Channel | — |
| AirPods Max 2 (A3454) | unknown | Model via device info only | — | Channel (unverified) | — | — | — | Channel | — |
| Beats earbuds with case (Powerbeats Pro/Pro 2, Studio Buds/+, Fit Pro, Solo Buds) | various | Operable | Operable | — | — | — | — | — | — |
| Beats headphones / neckband (Solo3/Pro/4, Studio3/Pro, BeatsX, Flex, Powerbeats3/4) | various | Operable | — | — | — | — | — | — | — |

Head tracking and head gestures are **Research** for models with head-tracking sensors (AirPods 3/4,
Pro, Max) and need the accessory channel.

## App features independent of the accessory

| Feature | Requirement |
|---|---|
| Battery history, alerts, connection history, finder (signal strength) | Any supported accessory, Android 8+ |
| Smart automation | Android 8+; place/Wi-Fi need location, activity needs the step detector sensor, app rules need Usage access |
| Equalizer (Auralynx playback) | Android 8+ |
| Equalizer (other players) | Android 9+ uses DynamicsProcessing, Android 8 uses Equalizer; only players that publish their audio session |
| Spatial audio (app-local) | Android 8+; designed for headphones |
| Recorder | Android 8+; Bluetooth headset microphone needs Android 12+ |
| Live translation (on-device) | Android 12+ **and** a system translation service (e.g. Android System Intelligence on Pixel) |
| Live translation (cloud) | Your own LibreTranslate server (HTTPS) |
| Speech recognition | A system speech recognition service; language detection needs Android 14+ |
| Companion linking (background monitoring) | Android 8+; presence observation needs Android 13+ |

## Android versions

| Android | API | Status |
|---|---|---|
| 8.0–8.1 | 26–27 | Supported (minimum). BLE scanning needs location permission. Not yet tested. |
| 9–11 | 28–30 | Supported. BLE scanning needs location permission. Not yet tested. |
| 12–15 | 31–35 | Supported. Not yet tested on hardware. |
| 16 | 36 | Supported. Emulator-tested (unit/UI/database tests, manual walkthrough). |
| 17 | 37 | Compiles against API 37; target API 36. Accessory channel still unavailable (public API rejects classic L2CAP). |

## Phone manufacturers

Not yet tested. Known factors that may differ: aggressive background restrictions (Xiaomi, Huawei,
some Samsung builds) affect the monitoring service; some vendors' system Bluetooth stacks report HFP
battery differently. Please report results with the hardware issue template.
