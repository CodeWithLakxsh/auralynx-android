# Apple Watch compatibility research

*Status: research only. No pairing, activation or data exchange is implemented, and none is claimed.*

## Objectives and findings

| Objective | Finding | Auralynx status |
|---|---|---|
| Device discovery | Apple devices broadcast Continuity BLE advertisements (Nearby Info 0x10, Nearby Action 0x0F, Handoff 0x0C, Find My 0x12, ...). These are observable by any BLE scanner. | **Implemented (passive observer)**: More → Experimental → Apple ecosystem observer |
| Device identification | Nearby Info carries an action code; published research associates code 0x0A with "watch on wrist and unlocked". Addresses are random and rotate; without keys a watch cannot be tied to a person or a specific unit. | Decoded and labelled "published interpretation"; not verified |
| Protocol analysis | Watch–iPhone pairing uses a proprietary, authenticated protocol over BLE and Wi-Fi with keys bound to the user's Apple Account and the paired iPhone. | Not attempted |
| Pairing | Activation of an Apple Watch requires an iPhone and Apple's activation servers (Activation Lock). An Android app cannot complete it. | **Not possible / not implemented** |
| Battery data | Exposed to the paired iPhone over the encrypted pairing channel only. | Not available |
| Notifications | Delivered from the paired iPhone (ANCS-like services over the encrypted link). | Not available |
| Health sync | HealthKit sync goes through the paired iPhone and iCloud. | Not available |
| Calls | Relayed by the paired iPhone. | Not available |

## Ethics and safety

- Auralynx never requests Apple Account credentials and never presents pairing screens.
- The observer only listens. It does not transmit, impersonate Apple devices, or attempt connections.
- Continuity advertisements can reveal device activity; the observer shows data only on screen and does
  not store it.

## Method for contributors

1. Enable experimental features, open the observer near an Apple Watch you own, and record which message
   types appear while the watch is on/off wrist, locked/unlocked.
2. Report observations (no raw addresses) in an issue. Treat all interpretations as hypotheses until
   reproduced by at least two people.

## Sources

Martin et al., "Handoff All Your Privacy: A Review of Apple's Bluetooth Low Energy Continuity Protocol",
PoPETs 2019(4). Celosia and Cunche, "Discontinued Privacy: Personal Data Leaks in Apple Bluetooth-Low-Energy
Continuity Protocols", PoPETs 2020(1). furiousMAC continuity dissector.
