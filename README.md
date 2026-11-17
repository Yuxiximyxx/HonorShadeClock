# HonorShadeClock

荣耀 MagicOS 通知中心时钟改造（LSPosed 模块）。

目标：把 MagicOS 11 通知中心（notification shade）顶部的时钟改成居中显示的大时钟。

## 当前版本：v0.1.0 探针版

只定位、不改样式。hook SystemUI 进程中 `TextView.setText` 的两个 CharSequence 重载，
文本命中时钟格式（HH:mm / HH:mm:ss）的 TextView 实例只 dump 一次：

- 运行时类名、id 及资源条目名
- textSize（px / sp）与当前文本
- 向上最多 8 层父链：每层的类名、id/资源名、宽高（px）、LayoutParams 运行时类名

输出双通道：`XposedBridge.log`（前缀 `[ShadeClockProbe]`）+
追加写入 `/sdcard/Download/shadeclock_probe.log`。全程只读，不修改任何视图属性。

## 使用

1. LSPosed 启用模块，作用域勾选「系统界面（SystemUI）」
2. 强停一次 SystemUI
3. 下拉一次通知中心，等时钟跳过一分钟
4. 取回 `/sdcard/Download/shadeclock_probe.log`

## 构建

GitHub Actions 手动触发 `Build & Release`（workflow_dispatch），
用仓库 Secrets 中的 keystore 签名并创建 Release。本地不构建。
