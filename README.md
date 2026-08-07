# AMCode — VS Code on Android

<p align="center">
  <strong>一个 APK。打开就是 VS Code。无需 Termux，无需 root。</strong>
</p>

<p align="center">
  English | <a href="#chinese">中文</a>
</p>

---

AMCode bundles [code-server](https://github.com/coder/code-server) with a Termux-native runtime into a single Android app. Open it and you're staring at a fully functional VS Code editor — write, compile, debug, all on your phone or tablet.

Big thanks to **[Termux](https://github.com/termux/termux-app)** for providing the ARM64-native Linux environment, and **[code-server](https://github.com/coder/code-server)** for bringing VS Code to the browser. AMCode simply puts these two great projects together in one APK.

## Installation & First Launch

1. Download and install the APK from [Releases](https://github.com/whn-hjm/AMCode/releases)
2. Open AMCode — it will ask for **storage permission** (recommended: grant it, so your workspace is at `/sdcard/AMCode/`)
3. The app downloads ~236MB of packages on first launch (~5 minutes). You'll see live progress.
4. Once complete, VS Code appears.

## Usage

### Writing Code

Open any folder from the VS Code explorer. Your home directory is:
- `/sdcard/AMCode/` (if you granted storage permission)
- Internal app directory (if you chose internal storage)

### Terminal

Press `` Ctrl+` `` to open the terminal. It's a real **bash** shell. Install dev tools:

```bash
pkg install clang make cmake   # C / C++
pkg install python             # Python
pkg install rust               # Rust
pkg install openjdk-17         # Java
pkg install golang             # Go
```

### Extensions

Extensions work normally. Search and install from the VS Code marketplace.

## Requirements

| | |
|---|---|
| **Android** | 8.0+ (API 26+) |
| **Architecture** | ARM64 (most devices) or x86_64 (emulator) |
| **Storage** | ~1.5GB free (environment + packages) |
| **Network** | Required for first launch only. Offline after that. |

### Network Notes

- AMCode **auto-tests multiple mirrors** (Tsinghua, USTC, Official) and picks the fastest one for you.
- TUR packages (code-server itself) are downloaded from `tur.kcubeterm.com`. A VPN/proxy may be needed in some regions.
- Once installed, no network is needed. Everything runs locally.

### Terminal Notes

- The terminal is a real Linux shell (bash), not an Android shell.
- `pkg` is available — same package manager as Termux, with 3000+ packages.
- The `~` directory maps to your workspace, not the Android file system root.

## Architecture

```
                     AMCode APK
┌──────────────────────────────────────────┐
│                                          │
│  ┌─ WebView ───────────────────────┐    │
│  │  http://localhost:8080           │    │
│  │  ┌────────────────────────────┐  │    │
│  │  │  VS Code (code-server)     │  │    │
│  │  │  • Editor                  │  │    │
│  │  │  • Terminal (bash)         │  │    │
│  │  │  • Extensions marketplace  │  │    │
│  │  └────────────────────────────┘  │    │
│  └──────────────────┬───────────────┘    │
│                     │                     │
│  ┌─ Foreground Service ─────────────┐    │
│  │  Health checks every 10s          │    │
│  │  Auto-restart on crash            │    │
│  └──────────────────┬────────────────┘    │
│                     │                     │
│  ┌─ Termux Native Runtime ──────────┐    │
│  │  /data/data/com.devbox/files/usr/ │    │
│  │  bash • node • git • clang • py  │    │
│  │  pkg/apt → 3000+ packages        │    │
│  │  Bionic libc, no proot overhead  │    │
│  └──────────────────────────────────┘    │
└──────────────────────────────────────────┘
```

## Build

```bash
# Prerequisites: JDK 21+, Android SDK 34+, Gradle 8.7
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release.apk
```

The Termux bootstrap files are downloaded automatically during build (cached in `app/src/main/assets/`).

---

<h2 id="chinese">中文说明</h2>

## AMCode — 在 Android 上运行 VS Code

**一个 APK，打开即用。** AMCode 将 code-server 和 Termux 原生运行环境打包成一个 App，让你在手机上拥有完整的 VS Code 开发体验。

本项目基于 **[Termux](https://github.com/termux/termux-app)** 提供的 ARM64 Linux 环境和 **[code-server](https://github.com/coder/code-server)** 提供的 VS Code Web 版本，感谢这两个优秀的开源项目。

## 安装与首次启动

1. 从 [Releases](https://github.com/whn-hjm/AMCode/releases) 下载 APK 安装
2. 打开 App —— 会提示**存储权限**（建议授予，工作目录将设为 `/sdcard/AMCode/`）
3. 首次启动会自动下载约 236MB 的依赖包（约 5 分钟），终端会实时显示进度
4. 安装完成后自动进入 VS Code 界面

## 使用说明

### 写代码

从 VS Code 的文件浏览器打开任意文件夹。你的工作目录：
- `/sdcard/AMCode/`（授予存储权限后）
- App 内部目录（选择内部存储时）

### 终端

按 `` Ctrl+` `` 打开终端。这是一个真正的 **bash** 终端，可以用 `pkg` 安装开发工具：

```bash
pkg install clang make cmake   # C / C++
pkg install python             # Python
pkg install rust               # Rust
pkg install openjdk-17         # Java
pkg install golang             # Go
```

### 扩展

VS Code 扩展市场正常使用，直接搜索安装即可。

## 使用条件

| | |
|---|---|
| **系统** | Android 8.0+ |
| **架构** | ARM64（大多数设备）或 x86_64（模拟器） |
| **存储** | 约 1.5GB 可用空间 |
| **网络** | 仅首次启动需要，之后完全离线 |

### 网络说明

- AMCode 会**自动测速多个镜像**（清华、中科大、官方），选择最快的使用。
- code-server 本体从 TUR（`tur.kcubeterm.com`）下载，部分地区可能需要代理。
- 安装完成后完全离线运行，不需网络。

### 终端说明

- 终端是标准 Linux bash，不是 Android shell
- 包管理器 `pkg` 可用，3000+ 软件包
- `~` 目录映射到你的工作目录

## 构建

```bash
# 需要：JDK 21+、Android SDK 34+、Gradle 8.7
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
# APK 在：app/build/outputs/apk/release/app-release.apk
```

Termux bootstrap 文件由 Gradle 在构建时自动下载（缓存于 `app/src/main/assets/`）。

## License

MIT — see [LICENSE](LICENSE)

---

AMCode is not affiliated with Microsoft, the VS Code project, or the Termux project.
