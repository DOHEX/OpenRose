# 默认样式桌面键盘验证

2026-10-26，Linux 离线场景。结果：[PASS](result.txt)。

两套风格均通过 Tab / Shift+Tab、Space / Enter 开关和单选操作、选择器内 Tab、第一次 Escape 关闭选择器、第二次 Escape 返回首页。首页 679/680dp 单双列和导航 899/900dp 底栏/侧栏边界也通过。

使用公开 ControlSession 和可控 i5 传输，只有 13 条初始化写入，没有设置命令或额外连接。所有截图和记录均为合成输入，不证明真实耳机、HyperOS 呈现或 Windows 启动效果。脚本：`.scratch/ui-default-styles-keyboard-smoke.sh`。

- [键盘控件记录](keyboard-controls.tsv)
- [首页布局边界](responsive-layout.tsv)
- [导航边界](navigation-boundary.tsv)

其余 PNG 为对应页面、焦点及选择器状态的截图。Material 3 对话框使用独立 semantics owner，焦点判定以最上层 owner 为准。
