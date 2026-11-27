# OpenRose Desktop Design System

OpenRose Desktop follows the Native Shell (NSH) Design Guides: semantic tokens in [theme.json](theme.json), motion tokens in [motion.ts](motion.ts), unstyled behavior primitives from `nsh/base`, and an app-owned `src/ui/` component layer. Pages import only from `src/ui/`.

## Appearance & Surface Tokens

- `"appearance": "system"` in [nsh.json](nsh.json) switches automatically between `light` and `dark` token sets in [theme.json](theme.json).
- Radii follow concentric geometry: `radius-sm` (`0.375rem`) for nested indicators, `radius` (`0.5rem`) for interactive controls and popovers, and `radius-lg` (`0.75rem`) for card and segmented containers.
- Status colors (`success`, `warning`, `destructive`) are reserved strictly for connection confirmation, in-progress/caution cues, and failure categories.

## Motion Inventory

All motion values are centralized in [motion.ts](motion.ts). Transitions run natively on the UI thread and respect `reduceMotion()` and `motion-reduce:`.

| Token / Pattern | Value | Used By | Purpose | Reduced Motion |
|---|---|---|---|---|
| `MOTION_DURATION.press` | `120 ms` | `ActionButton` | Tactile press feedback (`active:scale-[0.98]`) | Instant; scale stays `1.0` |
| `MOTION_DURATION.fast` | `150 ms` | `ActionButton`, `TextField`, `SelectField` | Color/border/focus transitions (`colorTransition`, `interactiveTransition`) | `motion-reduce:transition-none` |
| `MOTION_DURATION.normal` | `200 ms` | `Card`, `NoticeBanner`, `SelectField` popup | Entrance (`fadeInUp`) and popup fade/scale (`popupStarting` / `popupEnding`) | Lands immediately at rest |
| `MOTION_DURATION.emphasis` | `260 ms` | `StatusBadge` / noise confirmation feedback | Brief `pulse` or `shake` attention feedback on state transition | Skipped (`reduceMotion()`) |
| `MOTION_SPRING.snappy` | `stiffness: 520, damping: 34, mass: 1` | `SegmentedTabs` indicator | Native pill indicator travel between selected noise modes | Snaps immediately |
| `MOTION_SPRING.gentle` | `stiffness: 360, damping: 30, mass: 1` | Surface / layout settles | Smooth settling without overshoot | Snaps immediately |
| `MOTION_SCALE.press` | `0.98` | `ActionButton` (`active:scale-[0.98]`) | Pressed button scale feedback | Disabled under `motion-reduce:` |
| `MOTION_SCALE.popupClosed` | `0.96` / `scale-95` | `SelectField` popup | Subtle scale-in / scale-out over `bg-popover` | Appears immediately |
| `MOTION_DISTANCE.popupOffset` | `4 pt` | `SelectField` | Gap between trigger and dropdown list | Static `4 pt` |
| `MOTION_DISTANCE.entranceOffset` | `8 pt` | `createSurfaceMotion` | Initial surface reveal offset | `0 pt` |
| `MOTION_CLASS.colorTransition` / `interactiveTransition` | `150 ms` (`MOTION_DURATION.fast`) | `ToggleSwitch` (tray controls, background control) | Track color and thumb travel when a switch toggles | `motion-reduce:transition-none` |
