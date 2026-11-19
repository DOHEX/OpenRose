---
title: Features
description: Earphone controls, appearance, background control, and HyperOS integration in OpenRose.
---

## Earphone controls

What you see depends on the model and firmware; see [Supported earphones](../supported-earphones/).

- Battery for each earbud and the case, firmware version
- Noise control mode, with separate ANC and transparency levels
- Game mode, EQ presets, touch bindings, wear detection, prompt volume, multipoint, and audio codec preference
- On some models: custom EQ, gain and hearing protection, bass boost

## Appearance

- Miuix or Material 3 Expressive styles; switching keeps the earphones connected
- Light / dark, standard or floating bottom bar, background blur

## Background control

No root needed; see [Background control](../../guides/background-control/).

- Keeps the control connection after you close the app, with a running notification
- Restores control when the earphones reconnect after the app process was reclaimed or the phone restarted

## HyperOS integration

Requires an Xposed framework; see [HyperOS integration](../../guides/hyperos-integration/).

- Connection prompts and earphone status shown through the system island and focus notifications
- Earbud battery and noise mode switching in Fusion Device Center; its "Additional Settings" opens OpenRose directly
- Module control: the system Bluetooth process connects to the earphones, and OpenRose does not need to be open
- Headset circulation (experimental): move the earphones from one HyperOS audio source device to another through Fusion Device Center

## Privacy

OpenRose talks to the earphones directly over local Bluetooth and needs no account sign-up or sign-in.
