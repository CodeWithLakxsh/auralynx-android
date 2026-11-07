# Rootless accessory-channel compatibility (VendorID research)

*Status: 2026-11-07. Author: Auralynx project. Hardware validation: none yet.*

## Question

Can a normal Android app, without root, Shizuku, ADB, hidden APIs or a modified Bluetooth stack, open
the Apple Accessory Protocol (AAP) channel to AirPods and use the features that depend on it
(noise-control switching, conversation awareness, 1 % battery, press events)? And which features
additionally need the phone to present Apple's Device ID ("VendorID spoofing")?

## Background

- AAP runs over a **classic (BR/EDR) L2CAP** channel on **PSM 0x1001** (LibrePods "AAP Definitions";
  CAPod; apple-wireshark).
- Some features (loud-sound reduction, hearing assistance, transparency customisation) are reported to
  work only when the host's Device ID SDP record carries Apple's vendor ID 0x004C (LibrePods README,
  "VendorID spoofing"). Changing the phone's DID record requires system modification (root/Xposed).
- Older Android Bluetooth stacks had a bug that prevented AAP connections even with root workarounds;
  LibrePods reports this fixed in Android 16 QPR3 on Pixel, ColorOS/OxygenOS 16, and Android 17
  (Google issue 371713238). **That fix is in the stack, not a new public API.**

## Hypotheses

- **H0 (API):** Android's public SDK lets an app create a classic L2CAP socket to an arbitrary PSM.
- **H1 (channel):** On a phone where H0 holds, the app can open the AAP channel to connected AirPods.
- **H2 (protocol):** Once open, the accessory answers the documented initialisation sequence.
- **H3 (VendorID):** Without Apple's Device ID, the accessory still accepts Adaptive mode and the other
  non-VendorID controls (echo-confirmed).

## Method

1. **Source analysis** of the public Android SDK sources for API 36, 37.0 and 37.2
   (`sdkmanager "sources;android-37.0" "sources;android-37.2"`), files
   `android/bluetooth/BluetoothSocketSettings.java` and `android/bluetooth/BluetoothDevice.java`.
2. **Annotation analysis** of `platforms/android-37.0/data/annotations.zip`
   (`android/bluetooth/annotations.xml`).
3. **Runtime probe** in the app (`L2capAapTransport.probePublicApi`): builds
   `BluetoothSocketSettings` with `TYPE_L2CAP` and PSM 0x1001 and records the outcome. No radio activity.
4. **On-device experiment** (`ChannelExperiment`, More → Experimental → Run experiment): records device
   facts, attempts the channel through the public API, measures whether the accessory responds, and
   optionally whether Adaptive mode is accepted (restoring the previous mode). Produces JSON
   (`auralynx.channel-experiment/1`).

## Evidence

### E1 — public builder rejects classic L2CAP (API 37.0 and 37.2)

`BluetoothSocketSettings.Builder.setSocketType` (37.2, lines ~403–406):

```java
if (socketType != BluetoothSocket.TYPE_RFCOMM && socketType != BluetoothSocket.TYPE_LE) {
    throw new IllegalArgumentException("invalid socketType - " + socketType);
}
```

`setL2capPsm` (37.2, line ~429): `if (l2capPsm < 128 || l2capPsm > 255) throw new IllegalArgumentException(...)`,
and the published annotation is `@IntRange(from = 128, to = 255)` (LE dynamic PSM range).

### E2 — classic L2CAP factories are hidden

`BluetoothDevice.createL2capSocket(int)` and `createInsecureL2capSocket(int)` carry `@Hide` in 37.2.
They are non-SDK interfaces; apps targeting modern API levels cannot call them without bypassing the
runtime's hidden-API enforcement.

### E3 — runtime probe on Android 16

Emulator, Google `sdk_gphone64_arm64`, Android 16 (API 36), build `BE2A.250530.026.F3`:

```
transport_os_support: UNSUPPORTED
transport_probe: Public API rejects classic L2CAP on API 36: invalid socketType - 3
```

(`3` is `BluetoothSocket.TYPE_L2CAP`.) Captured from the Experimental screen of the v0.1.0-alpha debug build.

### E4 — how other projects connect

LibrePods and CAPod construct the classic socket through reflection on hidden constructors/methods
after calling a hidden-API bypass library (`HiddenApiBypass`), which manipulates ART internals to lift
the platform's restriction. Auralynx's charter forbids this ("do not circumvent Android security
boundaries through undocumented exploitation"), so the technique was not adopted.

## Results

| Hypothesis | Result | Basis |
|---|---|---|
| H0 | **Rejected** for API 26–37.2 | E1, E2 (source), E3 (runtime) |
| H1 | Not testable without H0 under Auralynx's constraints | — |
| H2 | Not tested on hardware. Protocol behaviour is unit-tested against published packets only. | — |
| H3 | Not tested on hardware. Community reports (LibrePods feature table) say Adaptive works without VendorID, while loud-sound reduction and hearing features need it. | Secondary source |

## Consequences for Auralynx

- The AAP implementation (codec, session, recovery, key exchange, capability registry) is complete and
  unit-tested but **inactive** on stock Android. All AAP-dependent features are displayed as
  "Not available on Android" with this explanation.
- Rootless features that **do** work everywhere: BLE proximity status (battery in 10 % steps, charging,
  in-ear, lid, model), standard Bluetooth connection state, HFP battery where reported, and all
  phone-side features (automation, EQ, recorder, translation, finder, analytics).
- The runtime probe will enable the channel automatically if a future Android version accepts
  `TYPE_L2CAP` + PSM 0x1001 in the public API.
- VendorID-dependent features remain **Root** in the compatibility matrix and are not offered.

## Limitations

- Source analysis covers AOSP; OEM builds could differ, but OEM changes cannot add public SDK API
  surface, and the runtime probe would detect any build that accepts the settings.
- No physical AirPods were available to this study, so H2/H3 are open.

## Reproducing

1. Install the debug APK on the phone under test, connect AirPods in Android settings.
2. More → Experimental → (optionally enable "Also test Adaptive mode") → **Run experiment**.
3. Save the JSON report and attach it to a "Hardware validation report" issue.
4. To repeat E1/E2 on a new SDK: `sdkmanager "sources;android-<N>"` and inspect the two files above.
