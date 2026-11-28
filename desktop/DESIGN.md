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

## Information Architecture

The main window is a sidebar (`SidebarNav`, vertical `Tabs`: arrow keys move between pages, Tab leaves the list) plus one scrolling page. Pages: 我的耳机 (dashboard and noise control), 添加耳机, 耳机信息, 诊断与记录, and 开发模式 (only with the simulator). #154 (tray controls) and #156 (headset settings) add their pages as further `NAV_ITEMS` entries and page branches in `App.tsx`.

- The sidebar footer always shows the current headset, its control state and the appearance; the title bar stays free for the platform's window controls.
- Reason categories from the control core (`connection_block_reason`, `reconnect_guidance`, `scan`, `connection_recovery`) are mapped to text only in `src/model/desktopPages.ts`; no page decides a rule.
- Every control has a `testID` and a readable `accessibilityLabel`; dev-only scenario controls (bonded headsets, platform state, scan advertisements) live on the 开发模式 page and drive the core through `OpenRoseHeadsetFlow`.
