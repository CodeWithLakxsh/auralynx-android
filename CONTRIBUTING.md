# Contributing to Auralynx

Thank you for helping. The most valuable contributions right now are **hardware test reports** and
**protocol evidence**.

## Ground rules

1. **No fabricated functionality.** A feature may appear as working only if a real code path exists
   and its transport is available. Otherwise show the limitation (see `CapabilityRegistry` and
   `FeatureSupport`).
2. **No fabricated data.** Show only values the accessory reported. Calculations are labelled as
   estimates and state their basis.
3. **Public APIs only** in the default build. No hidden-API bypass libraries, no reflection on `@hide`
   members, no system modification. Optional root integrations must live in a separate, opt-in module.
4. **Permissions on demand.** Request a permission only when the user enables the feature that needs it.
5. **Privacy.** No analytics, no automatic uploads. Diagnostics are exported only by explicit user action.

## Development setup

See [BUILDING.md](BUILDING.md). Before opening a pull request:

```bash
./gradlew testDebugUnitTest :core:common:test :core:protocol:test :core:automation:test :core:dsp:test
./gradlew :app:lintDebug
python3 scripts/check_licenses.py
python3 scripts/scan_secrets.py
```

Where possible, add tests next to the code you change:

- protocol layouts: `core/protocol/src/test` (assert exact bytes against a cited source);
- accessory behaviour: `core/bluetooth/src/test` with `FakeAccessoryConnection`;
- rules: `core/automation/src/test`; DSP: `core/dsp/src/test` with measurable assertions;
- database and UI: `androidTest` source sets (run with `connectedDebugAndroidTest`).

Mock tests must never be described as hardware validation.

## Hardware reports

Follow [docs/DEVICE_TESTING_CHECKLIST.md](docs/DEVICE_TESTING_CHECKLIST.md) and use the
"Hardware validation report" issue template. Attach the JSON from **More → Experimental → Run
experiment** and, if relevant, an exported diagnostic report (identifiers are redacted; review before
attaching).

## Protocol contributions

Cite your source for every new constant: a packet capture you made, a public document, or another
project's documentation (with its license). Captures may contain serial numbers or keys; redact them.

## Code style

Kotlin official style, 4-space indentation, max line length 140. Prefer small, pure, testable
functions in `core:*` modules and keep Android framework code thin.

## Licensing of contributions

By contributing you agree that your contribution is licensed under GPL-3.0-or-later. Do not submit
code you cannot license that way (for example code copied from proprietary sources or from projects with
incompatible licenses).
