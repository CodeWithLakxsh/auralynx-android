# Spatial audio engine: design and objective tests

*Status: experimental, app-local only.*

## Scope

- Processes **only audio played by Auralynx's own player** (and the same chain is available to
  recordings). It does not and cannot process other apps' audio.
- Uses a **generic spherical-head model** (Brown & Duda 1998), not measured or personalised HRTFs. It
  does not reproduce Apple's Personalized Spatial Audio.
- Android's own spatial audio (android.media.Spatializer) is reported on the screen but controlled by
  the system.

## Model

For each virtual source and ear: Woodworth interaural time delay (head radius 8.75 cm, c = 343 m/s,
fractional delay with linear interpolation, ≤ 64 frames) and a one-pole/one-zero head-shadow filter
`H(s) = (α(θ)s + β)/(s + β)`, `β = 2c/a`, `α(θ) = 1.05 + 0.95 cos(θ·180°/150°)`, discretised with the
bilinear transform. Modes:

| Mode | Processing |
|---|---|
| Crossfeed | Low-passed (700 Hz) and 0.3 ms-delayed contralateral feed at −11 dB, direct at −2.2 dB |
| Virtual speakers | L/R rendered as binaural sources at ±30°, summed, −6 dB output gain |
| Virtual room | Virtual speakers + 4 early reflections (11–29 ms, −8 to −12.5 dB, ±110°/±150°), normalised by total gain |
| Head-yaw rotation (experimental) | Source azimuths counter-rotated by a head-yaw estimate |

## Objective tests (unit tests in `core/dsp`, run on every CI build)

| Test | Criterion | Result |
|---|---|---|
| Lateral source ITD | Source at +90°: right ear leads left by the Woodworth delay (≈ 0.66 ms ≈ 31 frames @ 48 kHz) | Pass (31 ± 3 frames) |
| Lateral source ILD | Source at +90°: right-ear RMS > left-ear RMS | Pass |
| Frontal symmetry | Source at 0°: |L − R| < 1e-5 for random input | Pass |
| No clipping | 0.9 full-scale 200 Hz sine, every mode: peak ≤ 1.0 | Pass |
| Stage rotation | Head yaw +60° with mono content: left-ear RMS > 1.2 × right | Pass |
| Transparency when off | Mode OFF leaves samples bit-identical | Pass |

## Latency and cost

| Mode | Algorithmic latency (48 kHz) | Host DSP cost (EQ + spatial) |
|---|---|---|
| Off | 0 frames (0 ms) | 0.27 % of real time |
| Crossfeed | 16 frames (0.33 ms) | 0.27 % |
| Virtual speakers | 64 frames (1.33 ms) | 0.32 % |
| Virtual room | 64 frames (1.33 ms) | 0.50 % |

Host cost was measured by `DspBenchmarkTest` on an Apple M2 (JDK 17), 10 s of 48 kHz stereo in
1024-frame blocks. **These are not phone measurements.** On a phone, the Spatial audio screen shows the
measured DSP load, algorithmic latency and AudioTrack output buffer size while playing. Bluetooth codec
latency (typically 100–300 ms for A2DP) is added by the system and the headphones and is not included.

## Limitations

- Generic head model: no pinna cues, so elevation and front/back discrimination are weak.
- Head tracking needs the accessory channel, which is unavailable on stock Android (see
  ROOTLESS_COMPATIBILITY.md); calibration is manual and uncalibrated in absolute terms.
- No listening tests have been conducted yet.
