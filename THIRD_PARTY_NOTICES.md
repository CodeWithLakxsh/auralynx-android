# Third-party notices

## Libraries shipped in the APK

All 167 artifacts on the release runtime classpath are licensed under **Apache License 2.0** (165) or a
**BSD** license (2). Both are compatible with distribution under GPL-3.0-or-later. The complete,
machine-generated list with each artifact's declared license is in
[docs/DEPENDENCY_LICENSES.md](docs/DEPENDENCY_LICENSES.md) (regenerate with
`python3 scripts/check_licenses.py`; CI fails if an incompatible license appears).

Main components:

| Component | License | Copyright |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation, Room, DataStore, Glance, Splash Screen, Hilt integration) | Apache-2.0 | The Android Open Source Project |
| Jetpack Compose, Material 3, Material Icons | Apache-2.0 | The Android Open Source Project |
| Dagger / Hilt | Apache-2.0 | The Dagger Authors |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | JetBrains s.r.o. and contributors |
| javax.inject | Apache-2.0 | The JSR-330 Expert Group |
| JSpecify annotations | Apache-2.0 | The JSpecify Authors |
| Protocol Buffers runtime (repackaged inside `datastore-preferences-external-protobuf` and `glance-appwidget-external-protobuf`) | BSD-3-Clause | Google LLC |

The Apache License 2.0 text is available at <https://www.apache.org/licenses/LICENSE-2.0.txt>.

Protocol Buffers BSD-3-Clause notice: *Copyright 2008 Google Inc. All rights reserved.
Redistribution and use in source and binary forms, with or without modification, are permitted
provided that the following conditions are met: redistributions of source code must retain the above
copyright notice, this list of conditions and the following disclaimer; redistributions in binary form
must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution; neither the name of Google Inc.
nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.* No
Apache-licensed component in Auralynx ships a NOTICE file requiring additional attribution beyond the
above.

Auralynx deliberately uses **no** proprietary SDKs (no Google Play services, ML Kit, Firebase or
analytics). Location uses the framework `LocationManager`; translation uses the framework
`TranslationManager` or a user-configured LibreTranslate server; speech uses the framework
`SpeechRecognizer` and `TextToSpeech`.

## Test-only libraries (not shipped)

JUnit 4 (EPL-1.0), MockK (Apache-2.0), Turbine (Apache-2.0), AndroidX Test (Apache-2.0),
kotlinx-coroutines-test (Apache-2.0).

## Research sources

See [NOTICE](NOTICE). No code from LibrePods, CAPod or OpenPods is included; their documentation
informed the independent implementation.

## Documentation

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) is adapted from the Contributor Covenant, version 2.1,
<https://www.contributor-covenant.org/version/2/1/code_of_conduct.html>, licensed CC BY 4.0.

## Design

Colour, typography and accessibility checks were guided by the "UI/UX Pro Max" design guidelines
(Minimalism and Swiss style recommendations, WCAG AA contrast). The Auralynx mark (a point source
with three sound arcs) and all icons are original or from Material Icons (Apache-2.0).
