# Spec #33 UI smoke gallery

This renderer exercises the public `OpenRoseApp` entry point with synthetic state. It captures the three main pages, nearby devices, responsive breakpoints, and representative platform visibility. Keyboard mode checks focus traversal, Escape behavior, and setting callbacks. The screenshots verify layout and user-observable semantics; they do not prove real headset control or system presentation.

Source revision: `162256211a7e9120531ac93a2f3b8ac4e6d1680c` (`integration/spec-33-ui`).

Run it after the shared UI classes and desktop distribution have been built. The project build pins JDK 21; set `OPENROSE_UI_JAVA_HOME` to that JDK when the default `java` is another version:

```sh
OPENROSE_UI_JAVA_HOME=/path/to/jdk-21 OPENROSE_UI_KEYBOARD=1 tools/ui-review/spec33-smoke.sh
```

The script uses `OPENROSE_UI_JAVA_HOME`, then `JAVA_HOME`, then `java` from `PATH`. The Kotlin compiler dependencies are read from `GRADLE_USER_HOME` or `~/.gradle`. The default output directory is `output/ui-review/2026-10-27/spec-33/`; set `OPENROSE_UI_OUTPUT` to use a different directory. Compiled harness files and dependency path lists stay in `.scratch/`.

Final verification passed at source revision `162256211a7e9120531ac93a2f3b8ac4e6d1680c`: 35 shared UI tests, 176 core tests, and 2 Android adapter tests passed; the Android APK and Linux distribution built. The keyboard matrix contains 11 passing checks across both interface styles. The final gallery contains 40 screenshots; the 679/680dp and 899/900dp samples switch at the documented breakpoints, and no inspected screen overflows.
