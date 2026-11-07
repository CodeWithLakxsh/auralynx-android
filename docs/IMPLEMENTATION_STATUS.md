# Implementation status — v0.1.0-alpha

Legend: **Implemented** = real code path exists · **Tested** = automated tests cover it ·
**HW-verified** = confirmed on a physical accessory · **Experimental** · **Blocked** (external reason) ·
**Unsupported** (not possible for an Android app).

**No feature is HW-verified yet** (no accessory was available during development).

| Area | Feature | Implemented | Tested | HW-verified | Notes |
|---|---|---|---|---|---|
| Bluetooth | Adapter state, A2DP/HFP connection monitoring | ✅ | Indirect (engine tests use a mocked monitor) | ❌ | Broadcast-driven |
| | Apple accessory detection (SDP UUID / name) | ✅ | ❌ | ❌ | |
| | BLE proximity decoding (battery, charging, in-ear, lid, model) | ✅ | ✅ unit (layouts from published research) | ❌ | 10 % precision |
| | RPA resolution / encrypted battery | ✅ | ✅ unit (Bluetooth spec + OpenSSL vectors) | ❌ | Needs keys from AAP → currently unreachable |
| | Capability registry with evidence levels | ✅ | ✅ unit | ❌ | |
| | AAP codec & session (commands confirmed by echo) | ✅ | ✅ unit (fake accessory) | ❌ | **Blocked**: no public API for the channel |
| | Connection recovery (bounded backoff, fresh budget) | ✅ | ✅ unit (virtual time) | ❌ | |
| | HFP battery indicator | ✅ | ❌ | ❌ | Best-effort system broadcast |
| Accessory features | Listening mode, adaptive level, CA, long-press cycle, rename | ✅ | ✅ unit | ❌ | **Blocked** (AAP channel) |
| | Ear detection auto-pause/resume | ✅ | ❌ | ❌ | Needs AAP ear data (**Blocked**); BLE in-ear used for listening analytics |
| | Loud-sound reduction, hearing assistance | ❌ | — | — | **Unsupported** without root (VendorID) |
| | Heart rate | ❌ | — | — | Format undocumented; research |
| Battery analytics | History, precision labels, estimates, charge sessions, alerts | ✅ | ✅ unit + DB instrumentation | ❌ | |
| Smart automation | 7 condition types, 3 action types, conflicts, override, history, alarms | ✅ | ✅ unit + DB round-trip | ❌ | Listening-mode actions **Blocked** (AAP); audio-profile actions work |
| Equalizer | Presets, custom profiles, curve, app-local | ✅ | ✅ unit (filter response) + UI | n/a | |
| | Published audio sessions (DynamicsProcessing/Equalizer) | ✅ | ❌ | ❌ | Depends on players |
| Translation | System on-device engine, LibreTranslate engine, speech in/out, history | ✅ | ❌ (UI only) | ❌ | Availability depends on the phone's system services |
| Proximity finder | RSSI guidance, key-verified matching, last-seen | ✅ | ❌ | ❌ | |
| Security center | Permissions, history, redacted export, delete-all | ✅ | ✅ unit (redaction) + DB | n/a | |
| AirPods Max | Dashboard, sleep timer, listening analytics, exposure estimate | ✅ | UI | ❌ | Control **Blocked** (AAP) and unverified |
| Spatial audio | Crossfeed, virtual speakers, virtual room, latency/load | ✅ **Experimental** | ✅ unit (objective) | n/a | App-local only |
| Recorder | Record, noise reduction, voice enhancement, WAV/AAC export | ✅ | ✅ unit (DSP, WAV) | ❌ | |
| Gesture automation | Bindings, actions | ✅ | ❌ | ❌ | Press events **Blocked** (AAP) |
| System integration | QS tiles, widget, shortcuts, notification, companion linking, monitor service | ✅ | ❌ | ❌ | |
| Experimental | Channel experiment (JSON), Continuity observer, head-tracking viewer | ✅ **Experimental** | ✅ unit (decoders) | ❌ | Head tracking **Blocked** (AAP) |
| Apple Watch | Pairing, notifications, health, calls | ❌ | — | — | **Unsupported** |
| UI | 18 required screens + gestures/discovery | ✅ | ✅ Compose UI (5 navigation/honesty tests) | n/a | Emulator API 36 |

## Required screens

Splash ✅ · Onboarding ✅ · Permission setup ✅ · Device discovery ✅ · Device dashboard ✅ · Noise-control
dashboard ✅ · Battery analytics ✅ · Smart automation ✅ (+ rule editor) · Audio equalizer ✅ · Live
translation ✅ · Proximity finder ✅ · Security center ✅ · AirPods Max dashboard ✅ · Spatial audio ✅ ·
Audio recorder ✅ · Experimental features ✅ · Settings ✅ · About and licenses ✅. Each has loading, empty,
error or disconnected states where applicable.
