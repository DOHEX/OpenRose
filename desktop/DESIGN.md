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
| `MOTION_DURATION.normal` | `200 ms` | `Card`, `NoticeBanner` (`createSurfaceMotion`) | Entrance (`fadeInUp`) | Lands immediately at rest |
| `MOTION_CLASS.colorTransition` | `150 ms` | `ActionButton`, `TextField`, `SelectField`, `SegmentedTabs`, `CheckboxRow`, `RadioGroupField`, `ToggleSwitch` track, `StatusBadge`, `MetricGrid` | Color/border/focus transitions | `motion-reduce:transition-none` |
| `MOTION_CLASS.interactiveTransition` | `150 ms` | `ActionButton` | Tactile press feedback (`active:scale-[0.98]`) | `motion-reduce:transition-none`; scale stays `1.0` |
| `MOTION_CLASS.thumbTransition` | `150 ms` | `ToggleSwitch` thumb (used by `SwitchRow`, tray controls, background control) | Thumb travel when a switch toggles | `motion-reduce:transition-none` |
| `MOTION_CLASS.surfaceTransition` / `popupStarting` / `popupEnding` | `200 ms`, `opacity-0 scale-95` | `SelectField` popup | Popup fade and subtle scale over `bg-popover` | `motion-reduce:transition-none` |
| `MOTION_DISTANCE.popupOffset` | `4 pt` | `SelectField` | Gap between trigger and dropdown list | Static `4 pt` |

## Information Architecture

The main window is a sidebar (`SidebarNav`, vertical `Tabs`: arrow keys move between pages, Tab leaves the list) plus one scrolling page. Pages: 我的耳机 (dashboard and noise control), 添加耳机, 耳机设置, 耳机信息, 耳机流转, 诊断与记录, 托盘与后台, and 开发模式 (only with the simulator).

- The sidebar footer always shows the current headset, its control state and the appearance badge (light/dark); the title bar stays free for the platform's window controls.
- The main page shows the 型号配置 display name beside the battery status; the protocol family (e.g. ZT) appears only on 耳机信息 and 诊断与记录.
- Reason categories from the control core (`connection_block_reason`, `reconnect_guidance`, `scan`, `connection_recovery`) are mapped to text only in `src/model/desktopPages.ts`; no page decides a rule.
- Every control has a `testID` and a readable `accessibilityLabel`; dev-only scenario controls (bonded headsets, platform state, scan advertisements) live on the 开发模式 page and drive the core through `OpenRoseHeadsetFlow`.
