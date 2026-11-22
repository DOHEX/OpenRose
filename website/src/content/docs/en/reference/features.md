---
title: Features
description: Earphone controls, appearance, background control, and HyperOS integration in OpenRose.
---

## Earphone controls

What you see depends on the model and firmware; see [Supported earphones](../supported-earphones/).

- Battery for each earbud and the case, firmware version
- Noise control mode, with separate ANC and transparency levels
- Game mode, EQ presets, touch bindings, wear detection, prompt volume, multipoint, and audio codec preference
- Some models offer custom EQ, gain, hearing protection, and bass boost. FIRST ANGEL offers different presets in music and game modes.
- Astro X offers spatial and content modes, ear adaptation, and tap controls. The speakfeel2 variant of SPEAKFEEL offers independent microphone controls.
- Open the Android quick panel from the OpenRose Quick Settings tile or the focus notification to check battery levels and change noise or game mode. Tap **均衡器** (Equalizer) and choose an EQ preset from the menu that opens next to the row. The menu closes and the panel stays open. The row shows the new preset once the earphones confirm the change.
- After pairing your phone and computer for handoff, the i5 card in My Earphones, the desktop tray, and the phone quick panel shows the audio source when known. Change noise mode remotely, or move the headset to the phone or computer you are using. See the [desktop handoff pairing guide](../../guides/linux-desktop/#pair-a-phone-and-desktop).

## Appearance

- Miuix or Material 3 Expressive styles; switching keeps the earphones connected
- Light / dark, standard or floating bottom bar, background blur

## Background control

No root needed; see [Background control](../../guides/background-control/).

- Keeps the control connection after you close the app, with a running notification
- Restores control when the earphones reconnect after the app process was reclaimed or the phone restarted

## HyperOS integration

Requires an Xposed framework; see [HyperOS integration](../../guides/hyperos-integration/).

- Connection prompts and earphone status shown through the system island and focus notifications; tapping the focus notification opens OpenRose, the system headset page, RoseLink, or the quick panel
- Earbud battery and noise mode switching in Fusion Device Center; its "Additional Settings" opens OpenRose directly
- Module control: the system Bluetooth process connects to the earphones, and OpenRose does not need to be open
- Headset circulation (experimental): move the earphones from one HyperOS audio source device to another through Fusion Device Center

## Privacy

OpenRose talks to the earphones directly over local Bluetooth, needs no account, and the Android app has no network permission. See [Permissions and privacy](../permissions/) for what each permission is for.
