# OpenRose 第一轮 UI 图像生成提示词

工具：内置 `image_gen.imagegen`。以下为各页面实际使用的生成提示词；02 / 03 包含统一风格的修订，06 单独记录文案修订。01 作为后续页面的视觉参考。

## 01 · 01-headphones.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 01: 耳机 / connected overview.
Layout from top to bottom:
Status bar. Header "OpenRose" on left with very small subtitle "我的耳机", circular plus icon on right.
Centered hero occupying about 24% of screen: word "i7" in elegant medium size, a restrained silver earbud pair and charging case concept illustration, tiny muted pill "RS Common V2" and a green status dot with "已连接".
Three compact battery columns in a single white rounded card: "左耳 86%", "右耳 82%", "充电盒 未知", thin battery bars only for known values; charging case MUST say unknown, never fabricate percentage.
Main card "降噪模式", small secondary "设备已确认". Four equal icon buttons "标准", "降噪" selected blue, "通透", "更多". Beneath it one slim row "降噪档位" with selectable discrete chips "1", "3" selected, "5"; do not use continuous slider.
Two-column shortcut tiles "均衡器" with "原音" and small equalizer icon, and "触控操作" with "左右耳独立设置" and tap icon.
One compact grouped settings list of two rows: "音频与设备" with "AAC / SBC" trailing chevron; "设备详情" with trailing chevron.
Bottom tabs "耳机" selected blue, "系统", "设置". Tiny footer "概念设计 · 示例数据". Important: eye-catching headphone hero, readable content, no dense technical diagnostics on this page.
```

## 02 · 02-add-device.png

```text
undefined
Screen 02: 添加耳机 / nearby device discovery.
Status bar. Back arrow header "添加耳机", subtitle "扫描附近的 ROSE / ROSESELSA 设备".
Airy centered blue Bluetooth symbol within two thin pale blue concentric circles, compact helper "让耳机保持开启，并靠近手机".
A narrow quiet status row: Bluetooth icon "蓝牙已开启" and shield check icon "权限已授权".
Section "附近设备", right small scanning spinner and "扫描中".
Three vertically stacked beautifully proportioned white rows/cards: a silver earbud small icon, "ROSE i7", green subtle badge "已识别", secondary "RS Common V2 · −42 dBm", filled blue button "连接"; next "ROSE i5", badge "已识别", secondary "ZT · −58 dBm", outlined button "连接"; last "蓝牙设备", gray badge "未知型号配置", secondary "暂不支持连接", no active connect button.
Bottom helper rounded pale blue inset "只连接已识别的型号配置". Wide secondary button "停止扫描". Tiny footer "概念设计 · 示例数据". No bottom navigation because this is a detail screen. No invented history or pairing features.
Input image 1 role: EDIT TARGET, preserve its exact page content and control selections.
Input image 2 role: STYLE REFERENCE, the OpenRose homepage. Refine image 1 to match image 2: output a tall portrait 1:2 canvas with the homepage's #4D64EB periwinkle instead of bright cyan blue, matching pale-gray page background, white rounded grouped cards, typography and icon stroke. Use a consistent top toolbar with left back arrow and left-aligned title. Keep all exact device capability choices and Chinese wording. Do not add homepage content or duplicate navigation. Fit the entire screen at the same tall aspect ratio as image 2, with bottom Android gesture pill. Preserve all semantic labels and values. This is one edited screen, no side-by-side.
```

## 03 · 03-noise-control.png

```text
undefined
Screen 03: 降噪控制 / detailed noise controls.
Status bar. Back header "降噪控制". Small device label "i7 · RS Common V2" with green dot "已连接".
An understated sound-wave diagram behind two tiny silver earbuds, no large hero, 15% screen height maximum.
Section "降噪模式" with right small green check "设备已确认".
2 by 3 grid of equal white rounded icon options: "标准", "主动降噪" selected blue, "通透", "抗风噪", "自动", "动态降噪". All 6 modes distinct and readable. No invented mode.
White section card "降噪档位", three discrete wide pills "1 档", "3 档" selected blue, "5 档"; small caption "当前 3 档". This is discrete, no continuous slider and no transparency level visible when ANC is selected.
White section card "降噪轮换", helper "选择长按时轮换的模式", four small multi-select chips "标准" selected, "降噪" selected, "通透" selected, "抗风噪" unselected. Small quiet caption "按设备确认的选择显示".
Bottom a short subdued operation status inset with check icon "降噪档位已确认" and small trailing "刚刚".
Tiny footer "概念设计 · 示例数据". No bottom tab bar on detail page. Do not imply active ambient audio recording. Rounded clean crisp Chinese.
Input image 1 role: EDIT TARGET, preserve its exact page content and control selections.
Input image 2 role: STYLE REFERENCE, the OpenRose homepage. Refine image 1 to match image 2: output a tall portrait 1:2 canvas with the homepage's #4D64EB periwinkle instead of bright cyan blue, matching pale-gray page background, white rounded grouped cards, typography and icon stroke. Use a consistent top toolbar with left back arrow and left-aligned title. Keep all exact device capability choices and Chinese wording. Do not add homepage content or duplicate navigation. Fit the entire screen at the same tall aspect ratio as image 2, with bottom Android gesture pill. Preserve all semantic labels and values. This is one edited screen, no side-by-side.
```

## 04 · 04-equalizer.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 04: 均衡器 / sound equalizer.
Back header "均衡器", small device line "i7 · RS Common V2", green connected dot.
An understated top white card "当前设备音效" showing "原声再现 · HIFI" and muted small check "设备已确认".
Under it two slim segmented tabs "预设" and "自定义" with "自定义" selected blue. Small line "预设音效  弱水经典 / 原声再现 / 古典乐器 / 日系甜美" is optional; don't crowd.
Main large white card "十段 EQ" with warm neutral badge "未应用草稿". At top right simple text button "清零".
A thin blue frequency response curve over a very faint grid, then 10 vertical rounded slider tracks with blue thumbs. EXACT band labels: "20", "100", "300", "500", "1k", "2k", "3k", "5k", "8k", "15k"; very small "Hz" below. Integer values above tracks "+2", "+1", "0", "−1", "0", "+1", "+2", "+1", "0", "−1". Range −6 to +6. All ten tracks and labels aligned, clean and readable.
Small helper "编辑草稿不会改变耳机当前音效".
Full-width blue action "应用到耳机". Below button clear two-step progress preview in neutral gray "模式待确认" · "曲线待确认". This is an unsubmitted draft, NOT success or completed progress.
Small white row "游戏模式" with switch off and subtitle "设备已确认：关闭". Tiny footer "概念设计 · 示例数据". No extra features, no saving named presets, no graph that implies acoustic measurement. No bottom navigation on detail page.
Input image 1 role: STYLE REFERENCE ONLY: the previously generated OpenRose overview. Match its visual system, type scale, white cards, gray background, blue selection, icon stroke. Build the DIFFERENT detail page specified here, do not copy homepage content or product hero. Same portrait aspect ratio.
```

## 05 · 05-touch-controls.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 05: 触控操作 / gesture mapping.
Back header "触控操作", small "i7 · RS Common V2".
Top white grouped card: "触控总开关" with blue ON switch, subtitle "设备已确认：开启"; second row "佩戴检测" with blue ON switch, subtitle "配置检测功能".
Large segmented pill "左耳" selected blue / "右耳" unselected. Under it an elegant small silver earbud illustration with a pale blue dot marking the tap area and unobtrusive word "左耳". Illustration max 18% screen height.
One white grouped list of 4 roomy gesture rows with subtle separators, thin tap gesture icons, labels and trailing values with chevrons:
"单击" / "无操作"
"双击" / "播放 / 暂停"
"三击" / "上一曲"
"长按" / "降噪轮换".
Keep text "设备已确认" as small section subtitle.
Small helper bottom "点击一项，修改该手势绑定的操作". A secondary text line "绑定操作不会立即执行".
Show a slim pale-blue status note "左右耳可分别设置". Tiny footer "概念设计 · 示例数据". Do not show actual live wearing status, no playback buttons or media player. No bottom navigation on detail page.
Input image 1 role: STYLE REFERENCE ONLY: the previously generated OpenRose overview. Match its visual system, type scale, white cards, gray background, blue selection, icon stroke. Build the DIFFERENT detail page specified here, do not copy homepage content or product hero. Same portrait aspect ratio.
```

## 06 · 06-audio-device.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 06: 音频与设备 / audio device preferences.
Back header "音频与设备", small device line "i7 · RS Common V2". Use polished grouped settings at comfortable spacing, NO large product hero.
Section label "音频". Large white card with title "音频模式", small "设备已确认：AAC / SBC". Four selectable mode pills in two rows or one clean row: "AAC / SBC" selected blue, "LDAC", "LHDC", "LC3". Helper "耳机音频偏好，实际编码以系统连接为准". Then row "多点连接" with switch ON.
Section label "提示与音量". White card "提示音音量", five discrete steps "1", "2", "3" selected blue, "4", "5"; subtitle "设备已确认：3 档".
Two compact grouped rows beneath: "增益档位" with segmented choices "低", "中" selected, "高"; "听力保护阈值" with current "85 dB" trailing chevron. Small muted caption "型号阈值标签".
Section label "设备". White grouped rows "设备详情" / "RS Common V2" with chevron, "连接方式" / "BLE" with chevron. Full-width understated red text button "断开连接", plenty of spacing to avoid accidental tap.
Tiny footer "概念设计 · 示例数据". Assume example firmware supports shown gain/protection capabilities; all settings shown confirmed sample values, not measured performance. No firmware update or reset action invented. No bottom nav on detail page.
Input image 1 role: STYLE REFERENCE ONLY: the previously generated OpenRose overview. Match its visual system, type scale, white cards, gray background, blue selection, icon stroke. Build the DIFFERENT detail page specified here, do not copy homepage content or product hero. Same portrait aspect ratio.
```

## 07 · 07-system-integration.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 07: 系统集成 / top-level System tab.
Status bar, large heading "系统集成", subtitle "让耳机融入 HyperOS".
Top compact amber neutral notice with outlined info icon: "系统集成未就绪" and "偏好已保存，暂不产生系统展示". This is the truthful illustrated state, do not show verified integration.
White showcase card "超级岛" with a small centered black pill-shaped conceptual system notification preview: tiny earbud icon "i7", "L 86%   R 82%" in white; below preview small gray "样式预览".
Three-way segmented selector "不显示" / "官方" / "模块" with "模块" selected blue. Small helper "均由系统超级岛呈现".
Separate white card for independent "焦点通知" switch ON; subtitle "连接期间保留耳机状态通知". Below a tiny embedded illustrative notification preview with "i7  已连接" and "左耳 86% · 右耳 82%", no fake system verification.
Next white card title "模块显示时机", one row "连接时" with blue checkbox selected and helper "仅显示当前型号支持的事件". No wearing/removing/case trigger options.
Bottom clean row "运行状态与诊断" with amber trailing "待就绪" chevron.
Tiny footer "概念设计 · 示例数据". Bottom tabs "耳机", "系统" selected blue, "设置". All three settings are independent and visually separated. Do not create floating overlay permission request.
Input image 1 role: STYLE REFERENCE ONLY: the previously generated OpenRose overview. Match its periwinkle blue (not bright cyan), light neutral gray background, white rounded grouped cards, MiSans-like typography and precise icon stroke. Output same tall portrait 1:2 composition. Render ONLY the new screen described above, not homepage controls.
```

## 08 · 08-app-settings.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 08: 设置 / top-level app settings.
Status bar. Large heading "设置".
Section "界面风格". Two side-by-side elegant preview tiles labelled exactly "Miuix" selected with blue thin outline/check, "Material 3" unselected; show small abstract component thumbnails inside: Miuix flat round rectangular toggle and rows, Material 3 tonal pill buttons. Both are legitimate component styles. Tiny supporting line "切换风格，保留当前耳机连接".
Section "显示模式". White grouped rounded card holding 3 compact icon choices "跟随系统" selected blue, "浅色", "深色". They are separate from component style above.
Section "连接偏好". One white card row "强制使用 BLE" switch OFF, subtitle "仅影响下次连接". Below very small muted explanatory line "默认自动选择适用的连接方式".
Section "状态与信息". White grouped rows "运行状态与诊断" chevron, "关于 OpenRose" chevron with muted secondary "ROSE / ROSESELSA 耳机控制". About row is navigation only, no invented license or release details.
Near bottom an understated connected mini status "i7 已连接 · 控制会话保持运行". Tiny footer "概念设计 · 示例数据".
Bottom tabs "耳机", "系统", "设置" selected blue. Native Chinese settings visual, clean spacious cards, no login/notification counter/cloud features.
Input image 1 role: STYLE REFERENCE ONLY: the previously generated OpenRose overview. Match its periwinkle blue (not bright cyan), light neutral gray background, white rounded grouped cards, MiSans-like typography and precise icon stroke. Output same tall portrait 1:2 composition. Render ONLY the new screen described above, not homepage controls.
```

## 09 · 09-diagnostics.png

```text
Use case: ui-mockup
Asset type: high fidelity review mockup for the existing OpenRose Android headphone control app.
Primary request: redesign the real app into a polished, calm, premium native Android companion for ROSE / ROSESELSA earbuds. Chinese simplified UI. ONE mobile app screen per image.
Style: restrained Miuix-inspired component language, warm light gray page #F5F6F8, white rounded surfaces with 24dp radius, near-black #191B23 headings, quiet cool gray secondary text, periwinkle blue #4D64EB primary selected controls. Plenty of breathing room, precise alignment, clean thin line icons, sans serif Chinese typography like MiSans. Realistic accessible native 48dp controls. Almost flat, very subtle shadows. Crisp fine typography.
Composition: portrait 1:2 canvas, direct front-on full-bleed app screenshot, no external phone hardware frame, no surrounding poster, no perspective, no captions outside the UI. Draw the WHOLE screen with status bar at top reading 9:41 and Android navigation pill at bottom. Use 24dp page margins. No clipping. Match a 432dp-wide mobile logical viewport. Device sample across all images: i7, configuration RS Common V2. All values are illustrative review data, add tiny gray footer "概念设计 · 示例数据" above bottom navigation if there is one.
Shared navigation: top-level pages have a bottom bar with three evenly spaced destinations "耳机", "系统", "设置", headphone icon, system diamond icon, gear icon. Detail pages use back arrow + page title and have no bottom tab bar.
Product visual rule: earbuds may be a small tasteful 3D concept silhouette in silver/graphite, not a claim to reproduce real hardware. No album art, no playback/playlist UI, no marketing hero or giant words.
Constraints: Visual concept only. Keep visible connected/confirmed state and drafts semantically distinct. No firmware update, login, cloud, tracking, music library, spatial audio or other invented controls. Accurate readable Chinese text. No watermark.
Screen 09: 诊断与记录 / operational status and honest setting result.
Back header "诊断与记录", small subtitle "连接状态与设备确认".
White compact status card with 3 clear rows: "蓝牙权限" / green dot "已授权"; "蓝牙传输" / green dot "可用"; "系统模块" / amber dot "未就绪".
Section "当前连接". White card rows "设备" / "i7 · RS Common V2"; "控制连接" / "BLE"; "状态" / green dot "已就绪". Muted row "连接恢复记录" / "本次未触发".
Section "最近设置操作". White card with left small amber clock icon, title "自定义 EQ", neutral progress badge "部分确认". Three precise lines: "已发送 2 / 2 步"; green check "模式已确认"; amber clock "曲线待确认". Below divider thin tiny text "已写入不代表整项设置完成".
Second short white operation card green check "降噪档位 · 3 档" and "设备已确认".
One compact row "查看设备原始记录" monospace brackets icon and chevron, no fabricated hexadecimal log dump. One compact row "系统组件详情" chevron and tiny amber "待就绪".
Footer "概念设计 · 示例数据". No huge red errors, no conflating technical call success with hardware verification, no bottom tabs on detail page. Strong hierarchy, not an overwhelming developer console.
Input image 1 role: STYLE REFERENCE ONLY: the previously generated OpenRose overview. Match its periwinkle blue (not bright cyan), light neutral gray background, white rounded grouped cards, MiSans-like typography and precise icon stroke. Output same tall portrait 1:2 composition. Render ONLY the new screen described above, not homepage controls.
```



## 10 · 10-desktop-dark.png

```text
Use case: ui-mockup
Asset type: high-fidelity desktop redesign review screenshot for OpenRose, a Kotlin Compose Multiplatform headphone-control client.
Primary request: design a premium desktop dark appearance matching the visual language of the attached mobile app style reference.
Input image 1 role: STYLE REFERENCE ONLY for type hierarchy, restrained periwinkle blue selection and rounded grouped controls; translate this into a dark desktop visual system.
Composition: ONE direct front-on full-bleed desktop app window, landscape 16:10 canvas around 1920x1200, no laptop/monitor hardware, no perspective, no poster. Whole window visible. Crisp legible Simplified Chinese UI.
Visual system: dark gray #17191F page, #22252D surfaces, #2B2F38 secondary cards, bright warm-white text, muted gray secondary, #687DFF blue accents; matte finish, thin separators, 20px radii, very subtle shadows, thin outline icons, sophisticated large spacing.
Actual project constraint: desktop Bluetooth transport is not implemented yet. This is a DESIGN PREVIEW with example data, not a working connected state. At top of main content a small but clear neutral banner "桌面布局预览 · 以下为示例数据"; no claims of working Linux Bluetooth connection. Sidebar bottom small label "桌面蓝牙接入待完成".
Layout: slim top native titlebar "OpenRose", quiet window controls. A 220px wide left navigation sidebar: OpenRose with small line headphone emblem, navigation "我的耳机" selected with blue rounded background, "系统集成", "设置", "诊断与记录". Bottom sidebar "外观" and moon icon, version not needed.
Main workspace header "我的耳机", small subtitle "ROSE / ROSESELSA", right outlined plus button "添加耳机".
Under header neutral preview banner. Main two-column content: LEFT a large tall device showcase card, silver/graphite earbud pair and case concept art, "i7", "RS Common V2", small neutral badge "示例设备". Battery strip below "左耳 86%" / "右耳 82%" / "充电盒 未知".
RIGHT first white-on-dark card "降噪模式", small "状态示例", four icon choices "标准", "降噪" selected periwinkle, "通透", "更多"; lower row "降噪档位" with discrete "1", "3" selected, "5".
Below right two compact tiles "均衡器" subtitle "原声再现 · HIFI", "触控操作" subtitle "左右耳独立设置".
Below wide across workspace a grouped preferences card with two rows "音频与设备" / "AAC / SBC" and chevron, "设备详情" / "RS Common V2" and chevron.
Footer tiny text "概念设计 · 示例数据". No mobile bottom navigation. No music player, marketing tagline, login, playlist, firmware update or invented functionality. Honor existing settings structure and don't imply real device hardware appearance.
```
