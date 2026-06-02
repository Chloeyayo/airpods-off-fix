# AirPods Off Fix

中文 | [English](#english)

一个用于 ColorOS / OPlus 系统的 LSPosed 模块，用来修复 AirPods 在系统耳机设置里的两个问题：

- 阻止系统连接 AirPods 时发送禁用“关闭/Off”降噪模式的 AAP 指令，让“关闭”模式保持可用。
- 在 ColorOS MyDevices 详情页中显示并保持“关闭/Off”模式入口。
- 过滤 AirPods 连接后空闲 HFP 断开导致的 MyDevices 页面误灰显。

## 要求

- 已安装并启用 LSPosed。
- Android 8.0+。
- LSPosed API / min version: 101。
- 主要面向 ColorOS 16 / OPlus AirPods 集成。

## 推荐应用

模块已声明 LSPosed “推荐应用”，安装后 LSPosed 应自动提示/标记以下生效应用：

- `com.android.bluetooth`
- `com.heytap.accessory`
- `com.heytap.mydevices`

如果 LSPosed 没有自动勾选，请在模块作用域中手动选择以上三个应用，然后重启手机或重启这些目标进程。

## 安装

1. 从 [Releases](../../releases) 下载最新 APK。
2. 安装 APK。
3. 在 LSPosed 中启用模块，并确认“推荐应用”已选中。
4. 重启手机，或重启 Bluetooth / Accessory / MyDevices 相关进程。

## Non-root 实验版

仓库同时提供一个实验 non-root 版：`AirpodsOffFix-NonRoot-v0.1.apk`。

它不是 LSPosed 模块，不能注入系统蓝牙进程，因此不能阻止 ColorOS 发送禁用 Off 的包。它采用另一条路线：作为普通应用尝试连接 AirPods 的 L2CAP PSM `4097`，然后发送“恢复 Off 可用”和“切到 Off 模式”的 AAP 指令。

这个版本需要手动打开应用、选择已配对 AirPods 并点击 `Restore Off`。如果系统蓝牙进程已经占用同一通道，连接可能失败。

## 从源码构建

当前构建脚本是 Windows PowerShell 脚本，期望本地存在：

- JDK 8+，推荐 JDK 21。
- `sdk/android.jar`
- `sdk/build-tools/android-14/`，包含 `aapt2.exe`、`d8.bat`、`zipalign.exe`、`apksigner.bat`

运行：

```powershell
.\build_module.ps1
```

输出 APK：

```text
build\AirpodsOffFix.apk
```

构建 non-root 实验版：

```powershell
.\build_nonroot.ps1
```

输出 APK：

```text
build-nonroot\AirpodsOffFix-NonRoot.apk
```

## 说明

这是针对特定 ColorOS/OPlus 蓝牙与 MyDevices 行为的修复模块，不保证适用于所有 ROM、所有 AirPods 型号或所有系统版本。模块不包含原厂应用代码。

## English

An LSPosed module for ColorOS / OPlus devices that fixes two AirPods integration issues in the system earbud settings:

- Blocks the AAP command that disables the Noise Control `Off` mode when AirPods connect.
- Keeps the `Off` mode entry visible and usable in the ColorOS MyDevices AirPods detail page.
- Filters false MyDevices page grey-out caused by idle HFP profile disconnects after connection.

## Requirements

- LSPosed installed and enabled.
- Android 8.0+.
- LSPosed API / min version: 101.
- Mainly targeted at ColorOS 16 / OPlus AirPods integration.

## Recommended Apps

The module declares LSPosed recommended apps. After installation, LSPosed should suggest or mark these target apps:

- `com.android.bluetooth`
- `com.heytap.accessory`
- `com.heytap.mydevices`

If LSPosed does not select them automatically, select these three apps manually in the module scope, then reboot the phone or restart the target processes.

## Installation

1. Download the latest APK from [Releases](../../releases).
2. Install the APK.
3. Enable the module in LSPosed and confirm the recommended apps are selected.
4. Reboot the phone, or restart the Bluetooth / Accessory / MyDevices related processes.

## Non-root Experimental Build

The repository also provides an experimental non-root build: `AirpodsOffFix-NonRoot-v0.1.apk`.

It is not an LSPosed module and cannot inject into the system Bluetooth process, so it cannot block the ColorOS packet that disables the `Off` mode. Instead, it tries a different approach: as a normal app, it connects to the AirPods L2CAP PSM `4097`, then sends AAP commands to restore `Off` availability and switch to `Off` mode.

This version must be opened manually. Select a paired AirPods device and tap `Restore Off`. If the system Bluetooth process already owns the same channel, the connection may fail.

## Build From Source

The current build script is a Windows PowerShell script. It expects:

- JDK 8+, JDK 21 recommended.
- `sdk/android.jar`
- `sdk/build-tools/android-14/` containing `aapt2.exe`, `d8.bat`, `zipalign.exe`, and `apksigner.bat`

Run:

```powershell
.\build_module.ps1
```

Output APK:

```text
build\AirpodsOffFix.apk
```

Build the non-root experimental APK:

```powershell
.\build_nonroot.ps1
```

Output APK:

```text
build-nonroot\AirpodsOffFix-NonRoot.apk
```

## Notes

This module targets specific ColorOS/OPlus Bluetooth and MyDevices behavior. It is not guaranteed to work on every ROM, AirPods model, or system version. It does not include proprietary vendor app code.
