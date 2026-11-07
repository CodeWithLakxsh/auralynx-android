# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/) with pre-release labels.

## [0.1.0-alpha] - 2026-11-07

First development release. **Experimental: not yet validated with physical accessories.**

### Added
- Accessory engine: standard Bluetooth monitoring, BLE proximity decoding (battery, charging, in-ear,
  lid, model), model registry (AirPods, AirPods Pro, AirPods Max, Beats), capability registry that
  explains every unavailable feature.
- Apple Accessory Protocol implementation (encode/decode, session with echo-confirmed commands,
  bounded reconnect backoff, proximity-key exchange, RPA resolution, encrypted battery decoding).
  Opens only through public APIs; no public API exists as of Android 17 QPR2, so it is currently inactive.
- Battery analytics with real samples, precision labels, discharge estimates and charging sessions.
- Smart automation: time, place, Wi-Fi, accessory, app, activity and profile conditions; priority and
  specificity conflict resolution; manual override; history; single inexact alarm scheduling.
- 10-band equalizer with presets and custom profiles (app-local and published audio sessions).
- Experimental app-local spatial audio (crossfeed, virtual speakers, virtual room) with measured latency.
- Recorder with spectral noise reduction, voice enhancement, WAV and AAC export.
- Live translation with system on-device translation or a user-configured LibreTranslate server.
- Proximity finder, security & privacy center, AirPods Max dashboard, gesture bindings.
- Quick Settings tiles, home-screen widget, dynamic shortcuts, ongoing status notification,
  companion-device linking.
- Experimental: rootless channel experiment with JSON report, Apple Continuity observer, head-tracking viewer.
- CI (build, unit tests, lint, license audit, secret scan, emulator tests) and release workflow.
