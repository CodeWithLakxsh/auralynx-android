# Privacy policy

*Effective for Auralynx 0.1.0-alpha.*

Auralynx has **no account, no analytics, no advertising and no server**. It does not sell, share or
upload your data. Everything below stays on your phone unless you explicitly export it.

## What Auralynx stores on your phone

| Data | Why | Retention |
|---|---|---|
| Accessory name, model, firmware, Bluetooth address | Recognise your accessory | Until you delete data |
| Battery measurements | Battery history and estimates | Configurable, default 90 days |
| Connection events | Connection history, unexpected-disconnect alerts | Same as above |
| Automation rules, saved places (coordinates you saved), history | Smart automation | Rules/places until deleted; history per retention |
| Audio profiles | Equalizer | Until deleted |
| Recordings | Recorder | Until you delete them |
| Translation history | Translation (can be turned off) | Until cleared |
| Diagnostic log (off by default) | Troubleshooting | Last 5,000 entries |
| Accessory proximity keys | Identify your accessory in Bluetooth scans | Until you delete data |
| Phone location at accessory disconnect (off by default) | "Last seen" in the finder | Latest value only |

Recordings, keys and the database are excluded from Android cloud backup.

## Permissions

| Permission | When requested | Used for |
|---|---|---|
| Nearby devices (Android 12+) | First run | Connected-device state, BLE status broadcasts. Declared `neverForLocation`. |
| Location (Android 8–11) | First run | Android requires it for BLE scanning on these versions; Auralynx does not record location for this |
| Notifications | First run (optional) | Status and alerts you enable |
| Location (any Android) | Only when you add a place/Wi-Fi rule or enable last-seen location | Automation and finder |
| Background location | Only if you want last-seen location while the app is closed | Finder |
| Microphone | Only when you record or translate | Recorder and translation |
| Physical activity | Only when you add an activity rule | Step detection for automation |
| Usage access (system setting) | Only when you add an "app in use" rule | Detect the foreground app |

## Network

The only network use is **optional cloud translation**, off by default. If you enable it, the text
recognised from your speech is sent over HTTPS to the LibreTranslate server **you** configure. That
server's operator's privacy policy applies. Speech recognition is performed by your phone's system
speech service; if no on-device recognizer is available, that service (not Auralynx) may use the
network under its own policy.

## Diagnostics

Diagnostic reports are created only when you tap **Export report** and choose where to save them.
Bluetooth addresses and serial numbers are redacted. Nothing is sent automatically.

## Deleting your data

**More → Security & privacy → Delete all Auralynx data** removes every item above, including
recordings and stored keys. Uninstalling the app also removes all data.

## Children

Auralynx is a general-purpose utility and does not knowingly collect information from anyone.

## Changes

Changes to this policy are recorded in [CHANGELOG.md](CHANGELOG.md).
