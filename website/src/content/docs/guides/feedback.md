---
title: 反馈问题
description: 提交问题和需求时需要附带的信息。
---

问题和需求请提交到 [GitHub Issues](https://github.com/DOHEX/OpenRose/issues)。仓库提供了问题模板，按模板填写即可。

## 需要附带的信息

- 耳机型号和固件版本（耳机主页 → 耳机信息）
- 手机型号和系统版本
- OpenRose 版本（“设置” → “关于”）
- 复现步骤
- 涉及系统集成时：Xposed 框架版本，以及“状态”页进入诊断后显示的桥接状态

## 排查前先看

- 按界面提示在[常见问题](../troubleshooting/)里查找。
- 型号是否在[支持的耳机](../../reference/supported-earphones/)里，以及它的验证状态。
- 耳机是否已在系统蓝牙设置里连上音频。OpenRose 的控制连接跟随音频连接，见[快速开始](../getting-started/#控制连接与音频连接)。
- 后台控制没有恢复时，检查[后台控制](../background-control/)里对应系统版本的准备。

想参与开发，见[参与开发](../../development/contributing/)。
