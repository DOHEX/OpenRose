---
title: Supported earphones
description: Adapted ROSE / ROSESELSA earphone models, their control transports, and hardware verification status.
---

| Earphones | Control transport | Status |
| --- | --- | --- |
| EarFree i5 (EarFeel i5) | BLE | Reads verified on a real device; settings writes on Android not verified |
| EarFeel i7 | BLE or SPP | Offline tests only; not tested on a real device |
| BudsFeel MK2 | BLE or SPP | Not tested on a real device |
| OpenFeel MK2 | BLE or SPP | Not tested on a real device |
| Swift 2 | BLE or SPP | Not tested on a real device |
| Jasmine | BLE or SPP | Not tested on a real device |
| EarFree i3 | BLE | Not tested on a real device |
| HeadFeel (HeadFree) | BLE or SPP | Single-unit battery; not tested on a real device |
| AGATE, AGATE X, CERAMICS | BLE | Not tested on a real device |
| CERAMICS-X | BLE | Not tested on a real device |
| CERAMICS-X 7034, EXPLORER | BLE or SPP | Not tested on a real device |
| BudsFree i, SWIFT | BLE | Not tested on a real device |
| Cambrian, OpenFun, SportFeel (SportFree) | BLE or SPP | Not tested on a real device |
| OpenFeel (OpenFree, LDAC / LHDC / dual-codec branches) | BLE | Not tested on a real device |

Other models are not adapted yet.

## Notes

- The **control transport** is the Bluetooth link carrying the control connection. For models that support both, each connection picks one automatically from the model's capabilities, platform conditions, and the "Force BLE" preference; the preference only affects the next connection. The Linux app supports BLE only.
- **Hardware branches with the same retail name** are identified by advertisement prefixes. When the name is not enough to tell them apart, OpenRose does not pick a branch automatically.
- **Available settings** depend on the model and firmware version: settings without research evidence are neither shown nor written.

## Sources

- [Model adaptation evidence ledger](https://github.com/DOHEX/OpenRose/blob/main/docs/protocols/model-adaptation-ledger.md) (Chinese): each model's sources and omitted controls
- [Protocol research](https://github.com/DOHEX/OpenRose/tree/main/docs/protocols) (Chinese): the full protocol material

If your adapted earphones misbehave, or you can help verify a model, please [send feedback](../../guides/feedback/).
