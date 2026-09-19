# OCR-Monitor

Turn a repurposed Android phone into a Home Assistant sensor by pointing its
camera at a display that has no connectivity of its own.

A docked phone watches a meter, gauge or LCD panel; the app reads the value
on-device and publishes it to Home Assistant over MQTT, where it becomes a
normal entity with history, statistics and automations.

## Why

Plenty of useful things show a number and offer no way to get at it:

- A **Fossibot F3600** (1st gen) power station with no connectivity — its
  state of charge only exists on the front LCD.
- A **digital water meter** — the only place to detect a leak or a tap left
  running is the display in the cupboard.
- Gas and electricity meters, "dumb" weather stations, digital and analog
  thermostats.

The app is a sensor and nothing more. Leak detection, charge automation and
notifications all live in Home Assistant, where they belong.

## Status

Early design. See [docs/DESIGN.md](docs/DESIGN.md) for the architecture and
plan, and [docs/PROFILE-SCHEMA.md](docs/PROFILE-SCHEMA.md) for the device
profile format.

## Design principles

- **Never publish a number we aren't sure about.** A gap in history is
  recoverable; a phantom spike poisons statistics permanently.
- **No Google Play Services dependency.** Repurposed phones often have stale
  or absent Play Services.
- **Old hardware is a first-class target.** `minSdk 21`. Giving a drawer
  phone a second life is a feature, not a constraint.
- **Reading logic stays platform-agnostic.** The valuable half of this
  project should not be welded to Android.
