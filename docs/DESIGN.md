# OCR-Monitor — Design

## 1. Problem

Read a value off a physical display that has no data interface, and get it
into Home Assistant as a well-formed entity.

Two driving use cases, both **dock mode**: a phone permanently mounted and
pointed at a display, capturing on a timer, unattended.

| | Fossibot F3600 | Digital water meter |
|---|---|---|
| Value | State of charge, % | Cumulative volume, m³ |
| Sampling | Every 5 min is ample | Every 1 min (leak detection) |
| Display timeout | None — confirmed, stays lit | n/a |
| HA use | Automate charging outlet | Leak / tap-left-running alert |

Generalises to gas and electricity meters, weather stations, thermostats
(digital and analog needle).

## 2. Platform decision

**Native Android, dock-first.** Not a PWA, not cross-platform.

The deciding constraint: continuous unattended capture needs the camera alive
with the screen off.

- **Android** supports this. On API 29+ via a foreground service with
  `foregroundServiceType="camera"` (plus `FOREGROUND_SERVICE_CAMERA` on API
  34+); the camera service type is exempt from the runtime timeouts Android 15
  applies to other types. On **API 24–28 — the target dock device — none of
  that machinery exists yet**: a plain `startForeground()` service holds the
  camera with no types, no background-start restrictions and no runtime grant
  beyond `CAMERA`. Declaring the modern attributes is harmless on old devices
  and correct on new ones.
- **iOS cannot do this at all.** The camera session is suspended the moment
  the app leaves the foreground; there is no equivalent service. An iOS native
  app would buy nothing over a PWA. iOS is therefore out of scope, and if it
  is ever revisited it will be as a separate, smaller handheld product — not
  as the other half of this one.

Going native also removes the transport problem entirely: a native client
speaks **plain MQTT on 1883 over the LAN**. No WebSocket transport, no TLS, no
certificate. This matters concretely on Android 7.0, which predates ISRG Root
X1 in the system trust store and would likely reject a Let's Encrypt chain. If
TLS is wanted later, a native app can bundle its own trust anchors — something
a browser can never do.

### Reference dock device

Ulefone Power 2 (2017): Android 7.0, MediaTek MT6750T octa-core 1.5 GHz,
4 GB RAM, 6050 mAh.

- `minSdk 21`, `targetSdk` current. API 21 costs almost nothing over 24 and
  widens the set of reusable drawer phones.
- **No Google Play Services dependency** — hence no ML Kit. Not a loss: ML Kit
  is trained on print and performs poorly on seven-segment anyway.
- At one capture per minute the weak SoC is irrelevant. Even a two-second
  pipeline is comfortable.
- **Open risk:** 2017 MediaTek devices commonly report Camera2 `LEGACY`
  hardware level, a shim over Camera1. Manual exposure lock, focus distance and
  torch may be unavailable. CameraX degrades gracefully but capability must be
  probed at runtime and surfaced in the UI. **Verify on the real device in
  phase 1.**

## 3. Module layout

    core/     pure Kotlin, no Android imports
              profile model + (de)serialisation
              ROI geometry, anchor matching, transform fitting
              image ops against an abstract GrayImage
              seven-segment reader, digit classifiers
              validation and gating rules
              HA discovery payload construction

    app/      Android
              CameraX capture + foreground service
              on-device UI (deliberately thin)
              embedded web server (local config/monitor UI)
              MQTT client, storage, scheduling

`core/` stays free of Android types. This costs nothing today and keeps a
Kotlin Multiplatform conversion cheap if a second platform ever earns its
place. Adopting a cross-platform UI framework up front would be paying a real
cost now for a platform that cannot run the primary use case.

**Stack:** Kotlin, CameraX, Compose (on-device UI is minimal — the real
interface is the web UI), SQLDelight, an embedded HTTP server chosen for old-
API compatibility, and an MQTT client. Image processing is hand-rolled against
known ROI geometry where possible; OpenCV is a fallback if the reader needs it,
at the cost of ~30 MB of native libs.

## 4. Device profiles

A profile is a portable bundle, importable and shareable:

    profile.json      schema below
    reference.jpg     the reference frame
    samples/          labelled digit crops from user corrections

See [PROFILE-SCHEMA.md](PROFILE-SCHEMA.md) for the full schema and a worked
F3600 example.

The profile carries the **reference image** so the editor can overlay region
and anchor boxes on a real picture of the device — you see the map against the
thing it maps.

## 5. Alignment

A mounted phone drifts. Re-alignment makes unattended operation survivable.

**Anchors** are regions of the reference image covering content that never
changes: printed labels, fixed icons, bezel corners, panel seams.

Per capture:

1. Template-match each anchor by normalised cross-correlation, within a search
   window around its expected position.
2. Fit a transform from matched centres:
   1 anchor → translation · 2 → similarity (translate/rotate/scale) ·
   3 → affine · 4+ → homography, absorbing camera tilt.
3. Map every region through the transform before reading.

**The gates matter as much as the match.** Reject the frame if too few anchors
matched, if any peak correlation is below threshold, or if the fitted transform
is implausible (scale outside ~±15%, rotation beyond ~8°). A bad alignment that
silently reads digits from the wrong location is the most dangerous failure
mode in the system — worse than reading nothing. On rejection: publish
`unavailable`, retain the frame for review, raise a diagnostic.

**Anchor auto-suggestion.** In the editor, capture a burst over several minutes
and compute per-pixel temporal variance. A good anchor is *temporally stable
and spatially distinctive* — both, because a blank panel is perfectly stable
and matches everywhere equally. Propose candidates scoring well on both; the
user accepts or redraws. Prefer anchors spread across the frame and not
collinear.

**Drift as a diagnostic.** Publish correction magnitude to HA, so a creeping
mount produces a nudge before it produces an outage.

Re-alignment handles drift, not a knocked-over phone. A rigid mount remains the
first line of defence.

## 6. Reading pipeline

    capture (burst, median-combined)
      → grayscale
      → anchor match → fit transform → gate
      → per region: warp ROI to reference geometry
      → threshold (adaptive)
      → segment into digit cells
      → classify each cell
      → assemble value + confidence
      → validate
      → publish or withhold

**Seven-segment reading** does not use a general OCR engine. Tesseract is
trained on print and performs badly on seven-segment LCDs. Because the profile
pins the geometry, the robust approach is direct **segment-presence detection**:
for each digit cell, sample seven fixed sub-regions and decide on/off. It is
more accurate than general OCR here *and* far lighter. A small ONNX/TFLite digit
classifier is the fallback for LCD/LED fonts segment detection cannot handle.

**Other reader types** the profile dispatches to: printed digits, analog needle
(angle estimation), icon presence, LED colour, bar-segment count.

## 7. Validation and gating

For leak detection a wrong number is worse than no number. Enforced in `core/`:

- **Per-digit confidence**, with a floor below which nothing is published.
- **Monotonicity** — a cumulative meter can never decrease.
- **Delta plausibility** — bounded rate of change per unit time.
- **Range bounds** — SOC is 0–100 and cannot move 30 points in a minute.

Failing any gate publishes `unavailable` and retains the frame. User
corrections in the web UI are stored as labelled samples for later tuning.

## 8. Home Assistant integration

**MQTT discovery**, so entities appear correctly without YAML:

- retained config to `homeassistant/<component>/<node>/<object>/config`
- retained state to the configured state topic
- `device_class` / `state_class` / `unit_of_measurement` from the profile

Water uses `device_class: water` with `state_class: total_increasing`, giving
long-term statistics and energy-dashboard-style treatment for free. The leak
automation is then an ordinary HA template or a `utility_meter` helper.

An annotated frame is also published as an MQTT camera image, so HA shows what
the camera actually sees when a reading goes wrong.

**Diagnostics published alongside readings:** battery level, temperature, last
successful read, alignment drift, per-region confidence. This makes "no reading
in 30 minutes" a normal HA alert.

**Battery health.** A 6050 mAh cell held at 100% permanently will swell — the
usual way these docks die. Android 7 has no charge limiting, but the app
reports its own battery level, so HA can cycle a smart plug to hold the dock
between ~40% and ~80%. The dock maintains its own battery using the platform it
already reports to.

## 9. Local web UI

A phone in a meter cupboard is miserable to configure by hand. The app serves a
small local web UI:

- **Live overlay** — the current frame with regions drawn *after* the alignment
  transform, annotated with each region's value and per-digit confidence. Not
  the reference image; the live frame. This is the difference between "my boxes
  look right" and "it is reading correctly right now", and it is the primary
  debugging tool.
- **Profile editor** — draggable regions and anchors over the reference image,
  reader and parse settings, anchor auto-suggestion.
- **History and diagnostics** — recent readings, rejected frames with the
  reason, drift trend.
- **Correction** — fix a misread value; stored as a labelled sample.

Bound to the LAN. No authentication in v1 — treat as trusted-network only and
document that clearly.

## 10. Non-goals

- iOS, in any form, for now.
- Cloud anything. No account, no external service.
- Leak/charge/alert logic in the app. That is Home Assistant's job.
- Real-time or high-frequency capture. Once per minute is the design point.

## 11. Plan

1. **Camera spine.** CameraX preview, foreground service, timed capture,
   **screen-off capture verified on the actual Ulefone**, Camera2 capability
   probe (LEGACY level, torch, exposure lock). Prove the platform floor first.
2. **`core/` reading pipeline.** Profile model, anchor matching and transform
   fitting, seven-segment reader, gating. Unit-tested against real frames.
3. **MQTT sink.** Discovery, state, diagnostics — real entities in HA.
4. **Local web UI.** Live overlay first, then the profile editor.
5. **F3600 profile**, running unattended, driving a charging automation.
6. **Water meter profile.** Validates the abstraction; adds monotonic
   cumulative handling and the leak case.
7. Additional reader types: analog needle, icon, bar segments.

## 12. Prior art worth knowing

**jomjol/AI-on-the-edge-device** — ESP32-CAM firmware that reads water, gas and
electricity meters and publishes to Home Assistant over MQTT. Mature, widely
used, ~€10 of hardware, permanently powered. If the water meter alone were the
goal, it is the cheaper answer and worth evaluating first.

It does not replace this project: it will not handle the F3600 panel, a
thermostat or a weather station, its camera is far weaker, and it does not give
a general profile-driven tool. But it is honest prior art and may solve one of
the two use cases in a weekend.
