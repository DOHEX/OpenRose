# Spec #33 UI gallery

Generated through the public `OpenRoseApp` entry point by `tools/ui-review/spec33-smoke.sh` at source revision `162256211a7e9120531ac93a2f3b8ac4e6d1680c`.

All frames use synthetic UI state. The screenshots verify layout and visible semantics; they do not prove real headset control or HyperOS presentation.

Final verification passed: 35 shared UI tests, 176 core tests, and 2 Android adapter tests; Android APK and Linux distribution built. All 11 keyboard checks passed across Miuix and Material 3. See [keyboard.tsv](keyboard.tsv) for the per-check results and the `.txt` sidecars for semantics and bounds.

Visual review covered light and dark phone pages, the 360dp nearby-device pages, 1280dp connected home, ordinary Android/Windows/Linux platform visibility, and 679/680dp plus 899/900dp boundaries. The documented breakpoints switch as expected; at 680dp the connection status wraps to two lines inside its card. No inspected screen overflows.

Open [index.html](index.html) for the complete screenshot gallery.

Regenerate after the shared UI and desktop distribution have been built:

```sh
OPENROSE_UI_JAVA_HOME=/path/to/jdk-21 OPENROSE_UI_KEYBOARD=1 tools/ui-review/spec33-smoke.sh
```
