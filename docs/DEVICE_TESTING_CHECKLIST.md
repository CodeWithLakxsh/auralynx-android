# Device testing checklist

Record: phone model, Android version, build ID (Settings → About), Auralynx version, accessory model
number (e.g. A3048) and firmware. Use the "Hardware validation report" issue template.

## Setup
- [ ] Fresh install, onboarding completes, permission screen grants Nearby devices + notifications
- [ ] Accessory paired in Android settings; Auralynx dashboard shows it within 10 s
- [ ] Model name correct; "Identified …" evidence line plausible

## BLE status (all models)
- [ ] Left/right/case battery shown with "±10 % · advertisement"; values within 10 % of the iPhone/case LED reading if available
- [ ] Charging bolt appears when buds/case charge
- [ ] Taking one bud out changes "Wearing" (earbuds)
- [ ] Values update within ~30 s with screen off (battery history gains rows)
- [ ] Nearby advertisements list shows your accessory near the top (strongest signal)

## Accessory channel
- [ ] Experimental → Run experiment → save JSON (expected today: `H1_channel_opened: false`, probe "invalid socketType")
- [ ] Noise-control section shows "Not available on Android" with explanation (not an inert control)

## Monitoring and alerts
- [ ] Link accessory (Devices → Link accessory); after reboot, connecting the accessory shows the status notification without opening the app
- [ ] Low-battery alert fires at the threshold (lower the threshold to test)
- [ ] Unexpected-disconnect alert (enable first): turn accessory off while worn
- [ ] Notification disappears and service stops after disconnecting

## Automation
- [ ] Profile rule: "Studying → audio profile Podcast" applies the profile when toggled
- [ ] Time rule boundaries trigger within ~5 min (inexact alarm)
- [ ] Wi-Fi rule after granting location
- [ ] History shows APPLIED / FAILED_NOT_CONNECTED entries honestly

## Audio
- [ ] Equalizer: Auralynx player audibly changes with presets
- [ ] Equalizer on another player that publishes its session (e.g. a player with an "equalizer" setting); session listed under "Where it applies"
- [ ] Spatial audio modes audible; measured DSP load and latency shown
- [ ] Recorder: phone mic, headset mic (Android 12+); noise reduction and AAC export play back correctly

## Translation
- [ ] Language pair availability shown correctly; "Download in system settings" opens the system page where supported
- [ ] Spoken phrase is recognised, translated and spoken through the headphones

## Finder
- [ ] Signal bar rises when moving towards the case; "getting warmer/colder" plausible
- [ ] Last-seen location recorded after enabling it and disconnecting

## Matrix to cover
| Accessories | Phones | Android |
|---|---|---|
| AirPods 2/3/4, AirPods 4 ANC, Pro 1, Pro 2 (Lightning + USB-C), Pro 3, Max (Lightning/USB-C), one Beats earbud, one Beats headphone | Pixel, Samsung Galaxy, OnePlus/OPPO, Xiaomi, Motorola | 8, 10, 12, 14, 15, 16, 17 |
