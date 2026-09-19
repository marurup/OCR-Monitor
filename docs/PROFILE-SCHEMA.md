# Device profile schema

A profile is a portable bundle:

    <profile-id>/
      profile.json
      reference.jpg
      samples/            labelled digit crops from user corrections

`profile.json` is the map; `reference.jpg` is the territory it was drawn
against. The editor overlays regions and anchors on the reference so the map
can be checked against a real picture of the device.

All coordinates are pixels in **reference image space**, `[x, y, w, h]`, origin
top-left. Live frames are transformed into this space before reading, so
coordinates never need rewriting when the camera shifts.

## Top level

| Field | Meaning |
|---|---|
| `schemaVersion` | Integer. Currently `1`. |
| `id` | Stable slug, also the MQTT node id. |
| `name` | Human-readable device name; becomes the HA device name. |
| `reference` | Reference image filename and its dimensions. |
| `capture` | How to take a frame. |
| `anchors` | Fixed features used to re-align. See DESIGN.md §5. |
| `alignment` | Transform model and rejection gates. |
| `regions` | What to read, how to read it, and where it goes in HA. |
| `publish` | Global publishing policy. |

## `capture`

| Field | Meaning |
|---|---|
| `intervalSeconds` | Time between readings. |
| `warmupMs` | Settle time after the camera opens before the first frame. |
| `burst` / `burstStrategy` | Frames per reading and how to combine (`median` rejects sensor noise and flicker). |
| `torch` | `off` \| `on` \| `auto`. May be unavailable on LEGACY devices. |
| `focus` / `exposure` | Prefer locked on a static scene. Falls back to auto where the camera cannot comply. |

## `anchors`

Each anchor is a rect over content that never changes, plus `minScore`, the
normalised cross-correlation floor below which the anchor counts as unmatched.
Spread them across the frame; avoid collinear placement.

## `alignment`

| Field | Meaning |
|---|---|
| `model` | `translation` \| `similarity` \| `affine` \| `homography`. Needs 1 / 2 / 3 / 4+ anchors respectively. |
| `searchMarginPx` | How far from its expected position an anchor may be found. |
| `minAnchors` | Below this many matches, reject the frame. |
| `maxRotationDeg`, `scaleRange` | Plausibility gates on the fitted transform. |
| `onFailure` | `publish_unavailable` (default) — never guess. |

## `regions[]`

| Field | Meaning |
|---|---|
| `id`, `name` | Identity. |
| `rect` | Region in reference space. |
| `reader` | Reader type and its settings (below). |
| `value` | Units and the validation gates. |
| `homeassistant` | MQTT discovery mapping. |

### `reader.type`

- `seven_segment` — segment-presence detection over a pinned digit grid. The
  default for LCD/LED panels; see DESIGN.md §6 for why not general OCR.
- `printed_digits` — odometer-style or printed numerals.
- `analog_needle` — needle angle against a calibrated sweep.
- `icon` — presence/absence of a symbol.
- `led_colour` — discrete colour classification.
- `bar_segments` — count of lit segments in a bar gauge.

### `value` gates

`min` / `max`, `maxDeltaPerMinute`, and `monotonic` (`none` |
`increasing` | `decreasing`). A cumulative meter sets `increasing`, which
rejects any reading below the last accepted one. See DESIGN.md §7.

## Worked example — Fossibot F3600

```json
{
  "schemaVersion": 1,
  "id": "fossibot_f3600",
  "name": "Fossibot F3600",
  "reference": { "image": "reference.jpg", "width": 1920, "height": 1080 },

  "capture": {
    "intervalSeconds": 300,
    "warmupMs": 800,
    "burst": 3,
    "burstStrategy": "median",
    "torch": "auto",
    "focus": { "mode": "locked" },
    "exposure": { "mode": "locked", "compensation": 0 }
  },

  "anchors": [
    { "id": "logo",       "rect": [ 130,  90, 190, 80 ], "minScore": 0.72 },
    { "id": "input_icon", "rect": [1560, 140, 150, 120], "minScore": 0.72 },
    { "id": "bezel_bl",   "rect": [ 110, 900, 160, 140 ], "minScore": 0.70 },
    { "id": "unit_label", "rect": [1520, 880, 180, 120], "minScore": 0.70 }
  ],

  "alignment": {
    "model": "homography",
    "searchMarginPx": 60,
    "minAnchors": 3,
    "maxRotationDeg": 8,
    "scaleRange": [0.85, 1.18],
    "onFailure": "publish_unavailable"
  },

  "regions": [
    {
      "id": "soc",
      "name": "State of charge",
      "rect": [760, 300, 260, 120],
      "reader": {
        "type": "seven_segment",
        "digits": 3,
        "leadingBlankAllowed": true,
        "decimals": 0,
        "polarity": "dark_on_light",
        "threshold": { "method": "adaptive", "blockSize": 31, "c": 7 }
      },
      "value": {
        "unit": "%",
        "min": 0,
        "max": 100,
        "maxDeltaPerMinute": 5,
        "monotonic": "none"
      },
      "homeassistant": {
        "objectId": "f3600_soc",
        "name": "F3600 State of Charge",
        "deviceClass": "battery",
        "stateClass": "measurement",
        "unitOfMeasurement": "%"
      }
    }
  ],

  "publish": {
    "minConfidence": 0.85,
    "republishUnchanged": true,
    "expireAfterSeconds": 1800
  }
}
```

## Worked fragment — cumulative water meter

The difference that matters is `monotonic` plus the HA state class, which is
what earns long-term statistics and the energy-dashboard treatment.

```json
{
  "id": "total",
  "name": "Water total",
  "rect": [420, 560, 700, 150],
  "reader": {
    "type": "seven_segment",
    "digits": 7,
    "decimals": 3,
    "polarity": "dark_on_light"
  },
  "value": {
    "unit": "m³",
    "min": 0,
    "max": 99999.999,
    "maxDeltaPerMinute": 0.05,
    "monotonic": "increasing"
  },
  "homeassistant": {
    "objectId": "water_total",
    "name": "Water total",
    "deviceClass": "water",
    "stateClass": "total_increasing",
    "unitOfMeasurement": "m³"
  }
}
```
