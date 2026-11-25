---
title: Feedback
description: What to include when reporting bugs or requesting features.
---

Report bugs and request features in [GitHub Issues](https://github.com/DOHEX/OpenRose/issues). The repository provides issue templates; fill in the one that fits.

## What to include

- Earphone model and firmware version (earphone home → headset info (耳机信息))
- Phone model and OS version
- OpenRose version (Settings (设置) → About (关于))
- Steps to reproduce
- For system integration problems: the Xposed framework version and the bridge status shown in diagnostics (reached from the Status page)

## Check first

- Look up the message you see in [Troubleshooting](../troubleshooting/).
- Whether your model is in [Supported earphones](../../reference/supported-earphones/), and its verification status.
- Whether the earphones are connected to audio in the system Bluetooth settings. OpenRose's control connection follows the audio connection; see [Getting started](../getting-started/#control-connection-and-audio-connection).
- If background control does not come back, check the setup for your Android version in [Background control](../background-control/).

To contribute, see [Contributing](../../development/contributing/).
