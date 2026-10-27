# UI implementation smoke captures

All `synthetic-*.png` files are rendered through the public `OpenRoseApp` composable in `ImageComposeScene` using hand-built `ControlState` values. Device identity, battery readings, capabilities, platform readiness, and scan results are controlled synthetic examples for layout review; no Bluetooth transport, live control session, or hardware is involved. The application has no demo mode. Page captures are reached through semantic UI clicks. Sizes in filenames are pixel dimensions at density 1. There are 12 captures, including both Miuix and Material 3 phone home screens; the equalizer image shows a custom-curve draft after a semantic copy action.

| Screen | Capture |
| --- | --- |
| Phone home | `synthetic-01-home-light-420x900.png` |
| Add device | `synthetic-02-add-device-light-420x900.png` |
| Noise control | `synthetic-03-noise-light-420x900.png` |
| Equalizer | `synthetic-04-equalizer-light-420x900.png` |
| Touch controls | `synthetic-05-touch-light-420x900.png` |
| Audio and device | `synthetic-06-audio-light-420x900.png` |
| System integration | `synthetic-07-system-light-420x900.png` |
| Settings | `synthetic-08-settings-light-420x900.png` |
| Diagnostics | `synthetic-09-diagnostics-light-420x900.png` |
| Desktop dark | `synthetic-10-desktop-dark-1280x900.png` |
| Narrow disconnected | `synthetic-11-disconnected-narrow-360x900.png` |
| Material 3 phone home | `synthetic-12-home-material3-light-420x900.png` |
