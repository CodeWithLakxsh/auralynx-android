# Security policy

## Supported versions

Auralynx is in alpha. Only the latest release (currently `0.1.0-alpha`) receives fixes.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's *Report a vulnerability* (private security
advisory) on this repository. Include the version, Android version, steps to reproduce and impact.
We aim to acknowledge reports within 7 days.

## Security design

- **No root, no system modification, no hidden APIs.** Auralynx uses public Android APIs only.
- **Secrets at rest**: accessory proximity keys (IRK and advertisement key) and an optional
  user-supplied translation API key are encrypted with AES-256-GCM under a non-exportable Android
  Keystore key, stored in a private preferences file excluded from cloud backup and device transfer.
- **No hard-coded credentials.** CI scans every tracked file (`scripts/scan_secrets.py`).
- **Network**: cleartext traffic is disabled app-wide. The only network feature (optional cloud
  translation) requires an `https://` server configured by the user.
- **Exported components** are limited to the launcher activity (plus `auralynx://` deep links that
  only navigate), system-only Bluetooth broadcast receivers (guarded by `BLUETOOTH_CONNECT` and an
  action allow-list), the audio-effect session receiver required by Android's equalizer protocol, the
  widget receiver, Quick Settings tiles (system-bound) and the companion-device service (bound only by
  the system via `BIND_COMPANION_DEVICE_SERVICE`).
- **Logging**: diagnostic logging is off by default; when on, Bluetooth addresses and serial numbers
  are redacted before anything is stored. Packets containing device information or keys are never kept
  in the packet log.
- **Release signing** keys are never committed; CI reads them from repository secrets and deletes the
  decoded keystore after the build.
- **Dependencies** are pinned in `gradle/libs.versions.toml`, the Gradle wrapper is checksum-pinned and
  validated in CI, licenses are audited, and Dependabot proposes updates.

## Accessory data you should know about

When the accessory channel is available, AirPods provide an identity-resolving key and an
advertisement encryption key so the phone can recognise them. Auralynx stores these keys only on the
device (encrypted) to identify *your* accessory in BLE scans and decrypt its battery broadcast. They
are deleted with **Security & privacy → Delete all Auralynx data**.
