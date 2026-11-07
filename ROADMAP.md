# Roadmap

Auralynx follows "working software over claims": items move to *done* only with tests, and to
*hardware-verified* only with real-device reports.

## 0.2 — Hardware validation
- Community test round on AirPods 2/3/4, Pro 1/2/3, Max and two Beats models across Pixel, Samsung,
  OnePlus/OPPO, Xiaomi; publish results in COMPATIBILITY.md.
- Calibrate BLE status decoding against real captures (lid state, single-bud cases).
- Battery widget refresh on new measurements.

## 0.3 — Accessory channel options
- Keep watching each Android release for a public classic-L2CAP API (the runtime probe enables it automatically).
- Design an **opt-in, separately distributed** transport plugin for users who accept non-SDK APIs or
  root, without changing the default build's guarantees.
- Hearing-protection and transparency customisation for users with VendorID-capable setups (opt-in module).

## 0.4 — Audio
- Optional native (C++) DSP path if on-device profiling shows the Kotlin DSP exceeds 2 % CPU.
- HRTF set selection (SOFA import) for the app-local spatialiser; objective ITD/ILD test report on device.
- Background playback service for the local player with MediaSession transport controls.

## Later
- Localisation (Crowdin or Weblate), RTL review.
- Wear OS companion tile (Android watches; Apple Watch remains research-only).
- Reproducible-build verification in CI.

## Not planned
- Apple Watch pairing/activation without an iPhone (not possible for an Android app).
- Access to Apple's Find My network.
- Any feature that requires Apple Account credentials.
