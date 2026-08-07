# AMCode Architecture

## Overview

AMCode packages a full VS Code development environment into a single Android APK. It embeds a Termux-native Linux userspace and runs code-server inside it, rendered through a WebView.

```
┌──────────────────────────────────────────────────────────┐
│                    AMCode APK                            │
│                                                          │
│  ┌─ User Interface (MainActivity) ───────────────────┐  │
│  │  WebView ← http://localhost:8080                    │  │
│  │  ┌────────────────────────────────────────────┐    │  │
│  │  │        VS Code (code-server)               │    │  │
│  │  │  • Monaco Editor                           │    │  │
│  │  │  • Integrated Terminal (node-pty → bash)   │    │  │
│  │  │  • Extension Host                          │    │  │
│  │  │  • File Explorer                           │    │  │
│  │  └────────────────────────────────────────────┘    │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌─ Process Manager (CodeServerLauncher) ──────────────┐  │
│  │  • Starts/stops code-server process                  │  │
│  │  • Monitors stdout for lifecycle events              │  │
│  │  • Reports status via StateFlow                      │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌─ Foreground Service (CodeServerService) ────────────┐  │
│  │  • Android Foreground Service (START_STICKY)         │  │
│  │  • Health check every 10s (HTTP /healthz)            │  │
│  │  • Auto-restart on crash (max 10 consecutive)        │  │
│  │  • Notification with Stop action                     │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌─ Environment Manager (TermuxManager) ───────────────┐  │
│  │  • Bootstrap extraction + binary patching            │  │
│  │  • Library symlink creation                          │  │
│  │  • apt/dpkg package management                       │  │
│  │  • Mirror auto-selection (Tsinghua/USTC/Official)    │  │
│  │  • Environment variables (PREFIX, PATH, LD_LIBRARY_) │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌─ Termux Native Runtime ─────────────────────────────┐  │
│  │  /data/data/com.devbox/files/usr/                    │  │
│  │  ├── bin/          bash, node, apt, dpkg, git, ...   │  │
│  │  ├── lib/          bionic-linked shared libraries    │  │
│  │  ├── etc/apt/      sources.list + apt config         │  │
│  │  ├── var/          dpkg database + apt cache         │  │
│  │  └── opt/nodejs-24/  TUR nodejs installation        │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### targetSdk = 28

Android 10 (API 29) introduced W^X enforcement: apps targeting API 29+ cannot execute binaries from `app_data_file` (SELinux domain). By setting `targetSdk = 28`, the pre-API-29 SELinux policy is applied, allowing binary execution. This is the same approach used by Termux.

### Binary Path Patching

Termux binaries have `/data/data/com.termux/files/usr` hardcoded as `$PREFIX`. We use `com.devbox` (exactly 10 characters — same as `com.termux`) and replace all occurrences in ELF binaries and text files after extraction:

```
/data/data/com.termux/files/usr → /data/data/com.devbox/files/usr
```

### No proot

Unlike CodeFA which uses proot + Ubuntu, AMCode runs Termux binaries directly on Android's bionic libc. This eliminates the ptrace overhead of proot. The cost is manual path patching and SELinux workarounds.

### dpkg --root --force-all

Termux packages (.deb) have `./data/data/com.termux/files/usr/` paths hardcoded in their data tarball. Using `dpkg --root=$PREFIX --force-all`:

- `--root` maps the installation root correctly
- `--force-all` skips maintainer scripts (which are killed by Android's seccomp filter via SIGSYS)

After installation, files are moved from `$PREFIX/data/data/com.termux/files/usr/*` to `$PREFIX/`, and text patching fixes remaining `com.termux` references.

## First-Launch Pipeline

```
App Start
  │
  ├─ [1/6] Create directory structure
  ├─ [2/6] Extract bootstrap from APK assets (31MB, no network)
  ├─ [3/6] Patch 337+ binaries (com.termux → com.devbox)
  ├─ [4/6] Create symlinks (sh→dash, libfoo.so.X→libfoo.so.X.Y)
  ├─ [5/6] Test apt mirrors → pick fastest → configure sources.list
  └─ [6/6] apt update → download code-server (~236MB) → dpkg install
           │
           ├─ Success → code-server starts → WebView loads VS Code
           └─ Failure → retry up to 3 times → give up with error
```

## Package Sources

| Repository | URL | Purpose |
|---|---|---|
| Tsinghua Mirror | `mirrors.tuna.tsinghua.edu.cn/termux` | Main packages (auto-selected if fastest) |
| USTC Mirror | `mirrors.ustc.edu.cn/termux` | Main packages (auto-selected if fastest) |
| Official | `packages.termux.dev/apt/termux-main` | Main packages (auto-selected if fastest) |
| TUR | `tur.kcubeterm.com` | code-server (only source) |

Mirror selection: at startup, the app tests all three main mirrors with HTTP HEAD requests and picks the fastest.

## Component Details

### TermuxManager

- **Singleton** object managing the entire Termux environment
- Extracts bootstrap from APK assets (30MB, architecture-specific)
- Patches binary paths in ELF headers and text files
- Manages apt/dpkg package installation
- Provides `execCommand()` for running native binaries

### CodeServerLauncher

- Manages code-server process lifecycle
- Finds the Termux-installed binary at `$PREFIX/bin/code-server`
- Launches with `--bind-addr 0.0.0.0:8080 --auth none`
- Monitors stdout for "HTTP server listening" event
- Health check via `GET /healthz`

### CodeServerService

- Android Foreground Service (survives activity destruction)
- Persistent notification with "Stop" action
- Health check every 10 seconds
- Auto-restart on crash (10 max consecutive, then gives up)
- `START_STICKY` — Android restarts service if killed

### MainActivity

- Full-screen WebView loading `http://localhost:8080`
- Loading overlay with live terminal output during first launch
- Storage permission dialog on first run (3 options)
- WebView JS bridge for native features (open URL, toast, etc.)

## File Layout

```
app/src/main/java/com/devbox/
├── App.kt                    # Application class, crash handler
├── MainActivity.kt           # WebView UI + permission dialogs
├── CodeServerService.kt      # Foreground Service, health checks
├── CodeServerLauncher.kt     # code-server process lifecycle
├── TermuxManager.kt          # Environment setup, apt/dpkg, mirror test
└── WebViewInterface.kt       # JavaScript ↔ Native bridge

app/src/main/assets/
├── bootstrap-aarch64.zip     # ARM64 Termux bootstrap (build-downloaded)
└── bootstrap-x86_64.zip      # x86_64 Termux bootstrap (build-downloaded)
```

## Data Flow

```
User opens app
  → MainActivity.onCreate()
    → checkStoragePermission() → show dialog if needed
    → setupWebView()
    → startCodeServerService()
      → CodeServerService.onStartCommand()
        → startForeground() with notification
        → TermuxManager.initialize()
          → extract bootstrap
          → patch binaries
          → create symlinks
          → test mirrors → configure apt
          → installPackages() (retry loop)
        → CodeServerLauncher.start()
          → ProcessBuilder("code-server --bind-addr 0.0.0.0:8080 ...")
          → waitForServerReady() (poll /healthz)
        → startHealthChecks() (every 10s)
      → onServerReady(url)
        → webView.loadUrl("http://localhost:8080")
          → onPageFinished → hide loading overlay
```

## Security Considerations

- **targetSdk=28**: Required to bypass W^X, but means newer security features are not enforced. Acceptable since the app runs entirely locally.
- **--auth none**: code-server has no password. Only accessible on localhost.
- **--force-all**: dpkg skips all package scripts. The scripts are not malicious — they're blocked by Android seccomp.
- **[trusted=yes]**: apt skips GPG verification. The Termux GPG key ring is not bundled in the bootstrap.
- **0.0.0.0 bind**: code-server listens on all interfaces. On a device without port forwarding, only localhost is reachable.

## License

MIT — see [LICENSE](LICENSE)
