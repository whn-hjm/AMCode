# AMCode — VS Code on Android

<p align="center">
  <strong>One APK. Open it. VS Code appears. No Termux. No root.</strong>
</p>

AMCode puts a full VS Code development environment on your Android phone or tablet.
It bundles [code-server](https://github.com/coder/code-server) with a Termux-native
runtime into a single app. Write, compile, and debug — all on your device.

| | AMCode | Termux | CodeFA | Codespaces |
|---|---|---|---|---|
| **Setup** | Install APK | Install 3 apps + configure | Install APK | Browser only |
| **Offline** | ✅ (after first launch) | ✅ | ✅ | ❌ |
| **Root** | Not required | Not required | Not required | N/A |
| **Editor** | Full VS Code | Terminal + manual setup | Full VS Code | Full VS Code |
| **Performance** | Native binary execution | Native | proot overhead | Cloud |

## Architecture

```
┌────────────────────────────────────────┐
│              AMCode APK                │
│                                        │
│  ┌─ WebView ───────────────────────┐  │
│  │  http://localhost:8080            │  │
│  │  ┌──────────────────────────┐   │  │
│  │  │   VS Code (code-server)  │   │  │
│  │  │   Editor · Terminal · Ext │   │  │
│  │  └──────────────────────────┘   │  │
│  └─────────────────────────────────┘  │
│                  │                      │
│  ┌─ Foreground Service ─────────────┐  │
│  │  Health checks every 10s         │  │
│  │  Auto-restart on crash           │  │
│  └──────────────────────────────────┘  │
│                  │                      │
│  ┌─ Termux Native Environment ──────┐  │
│  │  /data/data/com.devbox/files/usr/ │  │
│  │  bash · node · git · clang · py  │  │
│  │  pkg/apt → 3000+ packages        │  │
│  │  NO proot · Native bionic binary  │  │
│  └──────────────────────────────────┘  │
└────────────────────────────────────────┘
```

## How It Works

### First Launch (~5 minutes)

1. Extracts Termux bootstrap (31MB) from APK — no network needed
2. Patches binary paths (`com.termux` → `com.devbox`)
3. Downloads code-server from TUR repo (~200MB, via Tsinghua mirror in China)
4. Starts code-server on `localhost:8080`
5. WebView loads → VS Code appears

### Subsequent Launches

~5 seconds. Everything is already installed.

### Installing Dev Tools

Open the integrated terminal (`Ctrl+``) inside VS Code:

```bash
pkg install clang make cmake   # C / C++
pkg install python             # Python
pkg install rust               # Rust
pkg install openjdk-17         # Java
pkg install golang             # Go
```

## Features

- **Full VS Code experience** — extensions, themes, settings sync
- **Integrated terminal** — real bash with package manager
- **Multi-language** — C/C++, Python, Rust, Java, Go, Node.js, and more
- **Git integration** — clone, commit, push from within VS Code
- **No root required** — everything runs in userspace
- **Crash recovery** — foreground service monitors and restarts code-server

## Build

### Prerequisites
- JDK 21+
- Android SDK 34+
- Gradle 8.7

### Build the APK

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

For Chinese mainland users: mirrors are pre-configured in `settings.gradle.kts`.

## License

MIT — see [LICENSE](LICENSE)

---

AMCode is not affiliated with Microsoft or the VS Code project.
