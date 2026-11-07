# Architecture

## Modules

```
app                    Android application: UI (Compose), services, receivers, tiles, widget, DI wiring
core/common            JVM  · dispatchers, Clock, Hex, Outcome, Diag (redacting logger), Redaction
core/protocol          JVM  · AAP packet codec, Continuity / Proximity Pairing decoding, RPA + payload
                              crypto, accessory model registry, capability registry
core/automation        JVM  · rule model, AutomationEngine (evaluation, conflicts, override, scheduling),
                              validator, JSON codec
core/dsp               JVM  · biquads, 10-band EQ, FFT, spectrum, binaural spatialiser, noise reduction,
                              voice enhancement, WAV codec
core/bluetooth         Android · AAP transport + session, BLE scanner, system Bluetooth monitor,
                              AccessoryManager (single source of truth for accessory state)
core/data              Android · Room database, DataStore settings, Keystore SecretStore, repositories,
                              battery analytics, diagnostics, retention
core/audio             Android · app-local player pipeline, session effects, recorder, post-processing,
                              output/spatializer facts
core/designsystem      Android · Compose theme, tokens, components
feature/translation    Android · translation engines (system on-device, LibreTranslate), speech in/out
experimental/research  Android · Continuity explorer, rootless channel experiment, head-tracking interpreter
```

**Adaptation from the original plan.** The specification sketched one Gradle module per feature
(`features/*`, `experimental/*`). Auralynx instead keeps protocol, rule and DSP *logic* in pure-JVM
`core` modules (fast, deterministic unit tests without Android) and keeps feature *UI* in the `app`
module, grouped by package (`app.auralynx.ui.<feature>`). This keeps the build at 11 modules, which
matters on CI runners and 8 GB development machines. Feature UIs can be extracted into modules later
without touching the logic.

Dependencies point inward: `app` → `feature/*`, `experimental/*` → `core/*`; JVM modules never depend
on Android modules.

## Accessory engine

```
         SystemBluetoothMonitor ──connected devices, adapter state──┐
 BLE ─── ProximityScanner ──sightings (only while connected)────────┤
         AapTransport (public-API probe) ──AapConnection──► AapSession ──AapState/events──┤
                                                                                         ▼
                                                    AccessoryManager.accessories: StateFlow<List<AccessoryState>>
                                                          │  (model resolution, BLE matching, BatteryMerger,
                                                          │   CapabilityRegistry.resolve)
                     ┌──────────────────────┬─────────────┴──────────┬───────────────────────┐
                     ▼                      ▼                        ▼                       ▼
              AccessoryCoordinator      ViewModels/UI         AutomationRunner        Tiles/Widget/Notification
       (persist battery/events, alerts,
        auto-pause, sessions, gestures)
```

- **Transports are distinguished explicitly** (`Transport.STANDARD_BLUETOOTH`, `BLE_ADVERTISEMENT`,
  `ACCESSORY_CHANNEL`), and every feature resolves to a `FeatureSupport`: `Available(transport, evidence)`,
  `NeedsAccessoryChannel(reason)`, `RequiresPrivilegedAccess`, `Experimental` or `NotSupportedByModel`.
  The UI renders these states; it never decides availability itself.
- **Evidence**: model-declared features are `DECLARED` (or `UNVERIFIED` for AirPods Max); data received
  from the accessory upgrades them to `OBSERVED`, persisted per device.
- **Commands are confirmed**: `AapSession` subscribes before sending and reports success only when the
  accessory echoes the new value; a different echo is `UNSUPPORTED_BY_ACCESSORY`.
- **Recovery**: bounded backoff (0, 2, 5, 15, 30, 60 s); a session that worked earns a fresh budget, so
  long-lived connections never exhaust retries.
- **BLE matching**: an advertisement is attributed to a connected accessory only by IRK resolution
  (`KEY_VERIFIED`, enables 1 % decrypted battery) or, conservatively, by being the single same-model
  candidate with a strong signal (`PROBABLE`, labelled in the UI).
- **No polling**: the system monitor is broadcast-driven; BLE scanning runs only while an accessory is
  connected, in low-power batched mode; the finder uses low-latency scanning only while visible.

### Accessory channel status

`L2capAapTransport` uses only `BluetoothDevice.createUsingSocketSettings` and probes, without radio
activity, whether the platform accepts `TYPE_L2CAP` with PSM 0x1001. Android 16 and 17 (through 37.2)
reject it, so the channel is inactive and AAP features report `NeedsAccessoryChannel(OS_UNSUPPORTED)`.
The `AapTransport` interface allows an alternative transport to be plugged in by a separate, opt-in
build without changing the rest of the engine. See docs/research/ROOTLESS_COMPATIBILITY.md.

## Background execution

- `MonitorService` is a `connectedDevice` foreground service that runs **only while an accessory is
  connected** and stops itself otherwise.
- Starting it from the background is allowed through Android's companion-device exemption: users link
  the accessory with `CompanionDeviceManager`, and `CompanionPresenceService` starts monitoring when the
  device appears. Without linking, monitoring starts when the app is opened.
- Automation is event-driven (Flow `combine` + debounce) and schedules **one inexact alarm** for the
  next time-window boundary. Location uses the passive provider plus one-shot fixes; activity uses the
  batched step-detector sensor; Wi-Fi uses a network callback.

## Data and security

Room (`auralynx.db`, schema exported to `core/data/schemas`), DataStore for settings, Keystore-backed
`SecretStore` for keys. Retention cleanup runs at start-up. `Diag` redacts addresses/serials before any
sink sees a message; the database sink is attached only when the user enables diagnostics.

## Audio

- **Local player**: `MediaExtractor` → `MediaCodec` (PCM16) → `GraphicEqualizer` → `StereoSpatializer`
  → `AudioTrack` (float). Measures DSP load and reports algorithmic latency and output buffer size.
- **Session effects**: `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` → `DynamicsProcessing` (API 28+) or
  `Equalizer`. Applies only to publishing players while the process runs; the UI says so.
- **Recorder**: `AudioRecord` → streaming WAV in app-private storage via a `microphone` foreground
  service; offline spectral noise reduction and voice chain; AAC export with `MediaCodec`/`MediaMuxer`.
- C++/JNI was evaluated and **not used**: the Kotlin DSP measured 0.27–0.50 % of real time on a desktop
  JVM, so native code is not justified until on-device profiling shows otherwise.

## Testing

| Layer | Tests | Runs on |
|---|---|---|
| Protocol, automation, DSP, common | JUnit (byte-exact layouts, published test vectors, measurable DSP behaviour) | JVM |
| Accessory engine | JUnit + coroutines-test virtual time + `FakeAccessoryConnection` | JVM |
| Battery analytics, app logic | JUnit | JVM |
| Database & repositories | AndroidJUnit4, in-memory Room | Device/emulator |
| UI navigation & honesty checks | Compose UI tests with Hilt | Device/emulator |

Mock-based tests validate logic, not hardware behaviour.
