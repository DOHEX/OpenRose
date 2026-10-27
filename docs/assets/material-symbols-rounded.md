# Material Symbols Rounded

These Compose vector resources are mechanically converted from Google’s official `google/material-design-icons` repository.

- Source commit: [`10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7) (2026-10-23).
- Collection: Material Symbols Rounded, static SVG exports.
- Variant: `FILL=0`, `wght=400`, `GRAD=0`, `opsz=24`; Google’s default `<name>_24px.svg` export encodes this variant. The sibling `_fill1_24px.svg` exports were not used.
- Conversion: preserve source path data; use the source SVG viewport, translating non-zero viewBox origins into the Compose viewport; set resource size to 24dp and path fill to opaque black so Compose can tint it. Several assets use `viewBox="0 -960 960 960"` and receive a +960 Y translation; `auto_awesome` omits `viewBox` and uses its 24×24 coordinate system.
- License: Apache License 2.0, copied from the same source commit to [`material-symbols-LICENSE.txt`](../../composeApp/src/commonMain/composeResources/files/licenses/material-symbols-LICENSE.txt).

## Use by interface style

Material 3 uses these Material Symbols Rounded resources throughout. Miuix uses the official `miuix-icons:0.9.4` library for general actions and navigation, including settings, adding, returning, integration, equalizer controls and logs. Where that release has no matching symbol, the same-meaning Material Symbol supplies headphones, Bluetooth, noise control, transparency, wind, automatic mode, touch, pending status, light/dark appearance and earbuds. Both branches use their own library's `Icon` renderer and current content color. The earbuds illustration is separate from these UI icons.

| Compose resource | RoseIcon | Upstream SVG path |
| --- | --- | --- |
| `msr_headphones` | `HEADPHONES` | [`symbols/web/headphones/materialsymbolsrounded/headphones_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/headphones/materialsymbolsrounded/headphones_24px.svg) |
| `msr_extension` | `SYSTEM` | [`symbols/web/extension/materialsymbolsrounded/extension_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/extension/materialsymbolsrounded/extension_24px.svg) |
| `msr_settings` | `SETTINGS` | [`symbols/web/settings/materialsymbolsrounded/settings_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/settings/materialsymbolsrounded/settings_24px.svg) |
| `msr_add` | `PLUS` | [`symbols/web/add/materialsymbolsrounded/add_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/add/materialsymbolsrounded/add_24px.svg) |
| `msr_arrow_back` | `BACK` | [`symbols/web/arrow_back/materialsymbolsrounded/arrow_back_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/arrow_back/materialsymbolsrounded/arrow_back_24px.svg) |
| `msr_chevron_right` | `CHEVRON` | [`symbols/web/chevron_right/materialsymbolsrounded/chevron_right_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/chevron_right/materialsymbolsrounded/chevron_right_24px.svg) |
| `msr_noise_control_off` | `NOISE` | [`symbols/web/noise_control_off/materialsymbolsrounded/noise_control_off_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/noise_control_off/materialsymbolsrounded/noise_control_off_24px.svg) |
| `msr_hearing` | `TRANSPARENCY` | [`symbols/web/hearing/materialsymbolsrounded/hearing_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/hearing/materialsymbolsrounded/hearing_24px.svg) |
| `msr_air` | `WIND` | [`symbols/web/air/materialsymbolsrounded/air_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/air/materialsymbolsrounded/air_24px.svg) |
| `msr_auto_awesome` | `AUTO` | [`symbols/web/auto_awesome/materialsymbolsrounded/auto_awesome_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/auto_awesome/materialsymbolsrounded/auto_awesome_24px.svg) |
| `msr_equalizer` | `EQUALIZER` | [`symbols/web/equalizer/materialsymbolsrounded/equalizer_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/equalizer/materialsymbolsrounded/equalizer_24px.svg) |
| `msr_touch_app` | `TOUCH` | [`symbols/web/touch_app/materialsymbolsrounded/touch_app_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/touch_app/materialsymbolsrounded/touch_app_24px.svg) |
| `msr_volume_up` | `AUDIO` | [`symbols/web/volume_up/materialsymbolsrounded/volume_up_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/volume_up/materialsymbolsrounded/volume_up_24px.svg) |
| `msr_info` | `INFO` | [`symbols/web/info/materialsymbolsrounded/info_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/info/materialsymbolsrounded/info_24px.svg) |
| `msr_bluetooth` | `BLUETOOTH` | [`symbols/web/bluetooth/materialsymbolsrounded/bluetooth_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/bluetooth/materialsymbolsrounded/bluetooth_24px.svg) |
| `msr_check` | `CHECK` | [`symbols/web/check/materialsymbolsrounded/check_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/check/materialsymbolsrounded/check_24px.svg) |
| `msr_schedule` | `CLOCK` | [`symbols/web/schedule/materialsymbolsrounded/schedule_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/schedule/materialsymbolsrounded/schedule_24px.svg) |
| `msr_dark_mode` | `MOON` | [`symbols/web/dark_mode/materialsymbolsrounded/dark_mode_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/dark_mode/materialsymbolsrounded/dark_mode_24px.svg) |
| `msr_light_mode` | `SUN` | [`symbols/web/light_mode/materialsymbolsrounded/light_mode_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/light_mode/materialsymbolsrounded/light_mode_24px.svg) |
| `msr_list_alt` | `LOG` | [`symbols/web/list_alt/materialsymbolsrounded/list_alt_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/list_alt/materialsymbolsrounded/list_alt_24px.svg) |
| `msr_earbuds` | `DEVICE` | [`symbols/web/earbuds/materialsymbolsrounded/earbuds_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/earbuds/materialsymbolsrounded/earbuds_24px.svg) |
| `msr_close` | `CLOSE` | [`symbols/web/close/materialsymbolsrounded/close_24px.svg`](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/close/materialsymbolsrounded/close_24px.svg) |
