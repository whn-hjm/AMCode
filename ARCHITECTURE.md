# AMCode Architecture

Describes AMCode v1.1.0 (versionCode 2): code-server 4.143.1 on Node.js 24.18.0, Termux bootstrap `bootstrap-2026.08.02-r1+apt.android-7`.

## Overview

AMCode packages a full VS Code development environment into a single Android APK. It embeds a Termux-native Linux userspace and runs code-server inside it, rendered through a WebView that talks to a loopback-only, password-protected server on a dynamic port.

```
┌──────────────────────────────────────────────────────────────┐
│                       AMCode APK                             │
│                                                              │
│  ┌─ User Interface (MainActivity) ──────────────────────┐   │
│  │  WebView ← http://127.0.0.1:<dynamic port>            │   │
│  │  (session cookie injected; no JS bridge registered)   │   │
│  │  ┌────────────────────────────────────────────┐      │   │
│  │  │        VS Code (code-server)               │      │   │
│  │  │  • Monaco Editor                           │      │   │
│  │  │  • Integrated Terminal (node-pty → bash)   │      │   │
│  │  │  • Extension Host (+ android-as-linux.js)  │      │   │
│  │  │  • Extensions: Open VSX                    │      │   │
│  │  └────────────────────────────────────────────┘      │   │
│  └───────────────────────┬────────────────────────────────┘   │
│                          │                                    │
│  ┌─ Process Manager (CodeServerLauncher) ───────────────┐   │
│  │  • Random 32-byte token per launch (0600 config)      │   │
│  │  • Ephemeral port pre-bind → --bind-addr 127.0.0.1    │   │
│  │  • Logs in via /login + /mint-key → HttpOnly cookie   │   │
│  │  • PID file + orphan sweep before each start          │   │
│  │  • Seeds user settings; relinks ripgrep; stdout watch │   │
│  └───────────────────────┬────────────────────────────────┘   │
│                          │                                    │
│  ┌─ Foreground Service (CodeServerService) ─────────────┐   │
│  │  • Health check every 10s (GET /healthz)              │   │
│  │  • Auto-restart on crash (max 10 consecutive)         │   │
│  │  • Notification: Stop + Storage actions                │   │
│  │  • stopWithTask + onTaskRemoved → no orphan server     │   │
│  │  • Detects half-configured dpkg state → --configure -a │   │
│  └───────────────────────┬────────────────────────────────┘   │
│                          │                                    │
│  ┌─ Environment Manager (TermuxManager) ────────────────┐   │
│  │  • Bootstrap extract + com.termux→com.devbox patch    │   │
│  │  • Mirror speed test (HTTP 200 required) → sources    │   │
│  │  • Offline bundle install or parallel-Range TUR fetch │   │
│  │  • ensurePkgSetup: marker fast path / full repair     │   │
│  │  • apt Dir::Bin::dpkg → amcode dpkg wrapper           │   │
│  └───────────────────────┬────────────────────────────────┘   │
│                          │                                    │
│  ┌─ Workspace (App.kt / HomePrefs) ─────────────────────┐   │
│  │  • Default internal HOME: /data/user/0/com.devbox/…   │   │
│  │    files/home   (no storage permission needed)        │   │
│  │  • ~/storage/shared → /storage/emulated/0             │   │
│  │  • Optional external HOME /sdcard/AMCode (noexec)     │   │
│  │  • One-time migration dialog for legacy installs      │   │
│  └───────────────────────┬────────────────────────────────┘   │
│                          │                                    │
│  ┌─ Termux Native Runtime ──────────────────────────────┐   │
│  │  /data/data/com.devbox/files/usr/                     │   │
│  │  ├── bin/          bash, node, apt, dpkg, pkg, ...    │   │
│  │  ├── libexec/amcode/  dpkg wrapper, android-as-linux  │   │
│  │  ├── etc/apt/      signed sources + keyrings          │   │
│  │  └── var/          dpkg database + apt cache          │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### targetSdk = 28

Android 10 (API 29) enforces W^X: apps targeting API 29+ cannot execute files from the `app_data_file` SELinux domain. `targetSdk = 28` keeps the pre-API-29 policy so native binaries can run — the same trade-off Termux makes, and the reason the app cannot ship on Google Play.

### Same-length package name

Termux binaries hardcode `/data/data/com.termux/files/usr` as `$PREFIX`. AMCode uses `com.devbox` — exactly 10 characters, like `com.termux` — so every occurrence can be replaced in place without changing string lengths:

```
/data/data/com.termux/files/usr → /data/data/com.devbox/files/usr
```

Patching covers ELF binaries, text files, and absolute symlinks.

### No proot

Termux binaries run directly against Android's bionic libc. No ptrace overhead, no emulated filesystem — at the cost of path patching and the SELinux workaround above.

### dpkg wrapper instead of --root / --force-all installs

Termux `.deb`s carry `./data/data/com.termux/files/usr/...` payload paths, and this app can neither create `/data/data/com.termux` nor chroot (app UIDs cannot). The mechanism:

- **Every apt install** (any package, at any time after setup): `apt.conf.d/99-amcode-dpkg` sets `Dir::Bin::dpkg` to `libexec/amcode/dpkg`, a wrapper around the real dpkg. On `--unpack` it unpacks the `.deb`, rewrites `data/data/com.termux → com.devbox` in payload files (text, ELF, absolute symlinks), repacks with `dpkg-deb --root-owner-group -Zxz -z1`, and hands the rewritten archive to real dpkg. Maintainer scripts then run normally.
- **The initial code-server/nodejs install** (online or offline bundle): `dpkg --instdir=$PREFIX --force-all --force-script-chrootless --unpack` (never `--root`, which would double the admin dir), then the payload is moved up to `$PREFIX` and the com.termux prefix is rewritten before `dpkg --configure -a` runs the pending scripts.
- The wrapper path is configured via apt config, so a `pkg`/`apt` upgrade cannot clobber it; the setup script verifies it on every launch.
- **Half-configured states self-heal**: if the startup health check sees unconfigured packages in the dpkg database (e.g. after an interrupted install, or the tolerated API-28 `py3compile` seccomp failure), it runs `dpkg --configure -a` before code-server starts.

### ensurePkgSetup: marker fast path

`amcode-pkg-setup.sh` repairs the package-manager layer idempotently (keyrings incl. TUR key, missing bootstrap symlinks, pkg cache path, dpkg wrapper config, signed TUR source, dpkg database path migration). A full run costs ~8-22 s, so a completion marker gates it: the marker key binds the APK versionCode, a hash of the setup assets, the installed dpkg/termux-tools/termux-keyring versions, and the pkg script's size/mtime, backed by a handful of cheap file checks (keys present, wrapper matches the bundled asset, pkg cache, `.amcode-ready`). Fast path: 30-130 ms. Any mismatch, corruption, or unhealthy probe runs the full repair exactly once; the marker is only rewritten after a clean run.

### Internal-first workspace

New installs default to an app-private internal HOME (`/data/user/0/com.devbox/files/home`) — no storage permission is requested on first launch. `~/storage/shared` is a symlink to `/storage/emulated/0` so users can still reach shared storage. The notification's **Storage** action (or the in-app dialog) switches internal ↔ `/sdcard/AMCode` in either direction; files are never moved, and switching to external warns about `noexec`. Legacy external installs get a one-time migration dialog. Because the watcher would descend into shared storage through the symlink, the launcher seeds `"**/storage/shared/**"` into `files.watcherExclude` in the user's `settings.json` before the server boots (additive; unparseable or deliberately non-object values are left untouched).

### Offline bundle

`-PofflineBundle=true -PofflineBundleArch=aarch64|x86_64` makes the build embed the pinned, SHA-256-verified `code-server` + `nodejs-24` `.deb`s as generated assets under `offline-debs/<arch>/` plus a JSON manifest. At first launch `tryOfflineBundleInstall()` installs them (only ~18 MB of small dependencies still come from the main mirror — TUR is never contacted) and falls back to the online path on any failure.

### Focus and IME chain

Android WebView focus is fragile when an overlay was showing. The chain that makes the keyboard reliable:

1. The loading overlay uses `descendantFocusability="blocksDescendants"`; the WebView is `focusableInTouchMode`.
2. When the overlay is dismissed, `ensureWebViewFocus()` moves Android focus (and thus the IME) to the WebView (`requestFocusFromTouch` as native fallback).
3. An `OnApplyWindowInsetsListener` maps the IME inset to the WebView's bottom margin (API-level-adjusted; legacy `adjustResize` semantics no longer resize on modern Android).
4. A JS focus shim re-asserts editor focus inside the page.

## First-Launch Pipeline

```
App Start
  │
  ├─ [1/6] Create directory structure (internal HOME unless migrated)
  ├─ [2/6] Extract bootstrap from APK assets (32 MB per ABI, no network)
  ├─ [3/6] Patch binaries + text (com.termux → com.devbox)
  ├─ [4/6] Create symlinks (SYMLINKS.txt: sh→dash, lib*.so.X → lib*.so.X.Y, …)
  ├─ [5/6] Test main mirrors (HTTP 200 required) → pick fastest → sources.list
  └─ [6/6] Install nodejs-24 + code-server
           ├─ Offline APK: install bundled .debs → pull ~18 MB deps (main mirror)
           └─ Online APK: download ≈127 MB from TUR (8 parallel HTTP Range
              connections, resumable) + ~18 MB deps → dpkg --instdir unpack
              → payload move/prefix rewrite → dpkg --configure -a
           │
           ├─ Success → code-server starts → session cookie → WebView loads VS Code
           └─ Failure → retry with diagnostics → error surfaced in the overlay
```

On every subsequent launch the same path short-circuits through the `ensurePkgSetup` marker and existing dpkg database — an update install over old data repairs itself instead of reinstalling code-server.

## Package Sources

| Repository | URL | Purpose |
|---|---|---|
| Tsinghua mirror | `mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main` | Main packages (auto-selected if fastest) |
| USTC mirror | `mirrors.ustc.edu.cn/termux/apt/termux-main` | Main packages (auto-selected if fastest) |
| Official | `packages.termux.dev/apt/termux-main` | Main packages (auto-selected if fastest) |
| TUR | `tur.kcubeterm.com` | code-server 4.143.1 + nodejs-24 24.18.0 (only source) |

Mirror selection tests all three main mirrors concurrently and requires an HTTP 200; the fastest responder wins. All sources are GPG-signed: the Termux main keyring ships in the bootstrap (key fingerprint `5A897D96E57CF20C`), and the TUR signing key (`6A5C550C260667D1`, bundled as `assets/tur.gpg`) upgrades the TUR source from the bootstrap's temporary `trusted=yes` to a fully signed source during setup. TUR has no mirrors, hence the offline APK variant.

## Component Details

### App.kt (incl. `HomePrefs`)

Application class plus the workspace/storage policy object. `HomePrefs` owns the internal↔external HOME preference, applies the internal HOME with `~/storage/shared` symlink, verifies writability with probe files on FUSE storage, handles revoked legacy permissions, tracks one-time flags (external trust warning, internal trust hint, migration prompt), and exposes the migration path for legacy installs.

### TermuxManager

- Extracts the ABI-specific bootstrap from APK assets and patches paths.
- Mirror speed test and sources.list configuration.
- Installs code-server/nodejs: offline bundle first (if present), otherwise parallel HTTP Range downloads from TUR with resume.
- Runs `amcode-pkg-setup.sh` via `ensurePkgSetup` (marker fast path) and self-heals dpkg state.
- Installs the dpkg wrapper into `libexec/amcode/` and points apt at it.

### CodeServerLauncher

- Picks a free port by binding `ServerSocket(0)` on `127.0.0.1` immediately before launch, so the port cannot collide and is never exposed.
- Generates a fresh 32-byte token per launch; writes only its SHA-256 hex into `cache/run/code-server.yaml` (`0600`); launches with `--bind-addr 127.0.0.1:<port> --auth password --config <0600 file>` (plus `--disable-telemetry --disable-update-check`, private user-data-dir and extensions dir). The token is never in argv/env/logcat; the stdout watcher additionally redacts it.
- Performs code-server's browser login itself: `POST /login` with the password → session cookie → verify via `POST /mint-key` (401/403 without a valid session) → inject the **HttpOnly** cookie into the WebView's CookieManager. The page never sees the password.
- Writes `files/run/code-server.pid` (pid, process start time, port); before each start, validates any recorded PID via `/proc` (start time + cmdline signature containing code-server's own arguments) and kills orphaned trees from earlier runs.
- Seeds `files.watcherExclude` user settings before boot; relinks TUR's `@vscode/ripgrep-universal` (15.2.0) at `rg`; injects `NODE_OPTIONS=--require .../android-as-linux.js` (kill switches: `$HOME/.amcode/disable-platform-shim` or `AMCODE_ANDROID_PLATFORM_SHIM=0`).
- Monitors stdout for readiness/lifecycle events and reports state via `StateFlow`.

### CodeServerService

- Foreground service; persistent notification with **Stop** and **Storage** actions (an internal restart action is used for HOME switches and auth recovery).
- Health check every 10 s (`GET /healthz`); auto-restart on crash, max 10 consecutive attempts.
- `stopWithTask` + `onTaskRemoved`: swiping the app away kills the whole code-server tree — no orphan processes.

### MainActivity

- Full-screen WebView loading `http://127.0.0.1:<dynamic port>` with the pre-authenticated session cookie.
- Loading overlay with live terminal output during first launch; the overlay's focus behavior feeds the IME chain (see Key Design Decisions).
- Storage/workspace dialogs: switch internal ↔ external, legacy migration prompt, external Workspace Trust warning, and the one-time internal Trust hint shown after the first successful page load (VS Code Workspace Trust itself is never bypassed).
- WebView debugging (`setWebContentsDebuggingEnabled`) is decided from `FLAG_DEBUGGABLE` **before** `setContentView`, so release builds keep it off.
- **No JavaScript bridge is registered** (see WebViewInterface.kt below).

## File Layout

```
app/src/main/java/com/devbox/
├── App.kt                    # Application class + crash handler; contains HomePrefs
│                             #   (internal/external HOME policy, trust hints, migration)
├── MainActivity.kt           # WebView UI, focus/IME chain, storage & trust dialogs
├── CodeServerService.kt      # Foreground service, health checks, notification actions
├── CodeServerLauncher.kt     # Dynamic port + auth token + cookie login, PID/orphan
│                             #   cleanup, platform shim, watcherExclude seed, ripgrep
├── TermuxManager.kt          # Bootstrap extract/patch, mirrors, offline/online install,
│                             #   ensurePkgSetup marker, dpkg wrapper deployment
└── WebViewInterface.kt       # Legacy JS↔native bridge class — retained but NOT
                              #   registered (no addJavascriptInterface call)

app/src/main/assets/
├── bootstrap-aarch64.zip     # Termux bootstrap, ARM64 (32,203,582 B, build-downloaded,
│                             #   SHA-256 pinned; missing file fails the build)
├── bootstrap-x86_64.zip      # Termux bootstrap, x86_64 (32,112,380 B, same pinning)
├── amcode-dpkg-wrapper.sh    # dpkg wrapper: rewrites com.termux→com.devbox payload
│                             #   paths on --unpack and repacks the .deb
├── amcode-pkg-setup.sh       # idempotent pkg-manager repair (keyrings, symlinks,
│                             #   wrapper config, signed TUR, dpkg db migration)
├── tur.gpg                   # TUR repository signing key (6A5C550C260667D1)
├── android-as-linux.js       # platform shim injected into the extension host
└── offline-debs/<arch>/      # (only with -PofflineBundle=true) pinned code-server +
                              #   nodejs-24 .debs + JSON manifest (generated)
```

## Data Flow

```
User opens app
  → App.onCreate() → HomePrefs applies workspace (internal default; migration
    dialog for legacy external installs)
  → MainActivity.onCreate()
    → setWebContentsDebuggingEnabled(FLAG_DEBUGGABLE)   [before setContentView]
    → setupWebView() (cookie handling, no JS bridge)
    → startCodeServerService()
      → CodeServerService.onStartCommand()
        → startForeground() (Stop + Storage actions)
        → TermuxManager.initialize()
          → [1..5/6] dirs, bootstrap, patch, symlinks, mirror test
          → ensurePkgSetup (marker fast path 30-130 ms / full repair 8-22 s)
          → [6/6] offline bundle or TUR download → dpkg unpack → configure
        → CodeServerLauncher.start()
          → orphan sweep (PID file + /proc cmdline validation)
          → ServerSocket(0) → dynamic port
          → random 32-byte token → SHA-256 hex → 0600 code-server.yaml
          → seed watcherExclude → launch code-server --auth password
          → waitForServerReady() (stdout + /healthz)
          → POST /login → cookie → verify POST /mint-key
        → startHealthChecks() (10 s interval, max 10 restarts)
      → onServerReady(url, cookie)
        → inject HttpOnly cookie → webView.loadUrl("http://127.0.0.1:<port>")
          → onPageFinished → hide overlay → ensureWebViewFocus()
          → one-time internal Trust hint
```

## Security

- **Loopback-only, dynamic port**: `--bind-addr 127.0.0.1:<ephemeral>`; the port is chosen by pre-binding a `ServerSocket(0)` and is different on every launch. Other devices cannot reach the server.
- **Password auth, token hygiene**: fresh 32-byte random token per launch; only its SHA-256 hex reaches disk (a `0600` file in the app's cache dir). The token never enters argv, environment, or logcat; the stdout monitor redacts it defensively, and code-server's own debug logging redacts passwords too.
- **Cookie, not password, in the page**: the launcher performs the `/login` + `/mint-key` handshake and injects the HttpOnly session cookie into the WebView. Cross-frame cookie access is restricted (`setAcceptThirdPartyCookies(false)`).
- **No JS bridge**: `WebViewInterface` is deliberately not registered; navigation outside the exact local code-server origin is blocked.
- **WebView debugging off in release**: `setWebContentsDebuggingEnabled` is driven by `FLAG_DEBUGGABLE` before the content view exists.
- **Orphan cleanup**: PID file with start-time validation and cmdline signature matching kills leftover code-server trees; `stopWithTask`/`onTaskRemoved` prevent new ones.
- **Signed repositories**: main repo keyring (5A897D96E57CF20C) + bundled TUR key (6A5C550C260667D1); the bootstrap's temporary `trusted=yes` is removed once keys are installed.
- **Workspace Trust is not bypassed**: one-time hints explain trust; VS Code keeps its own restricted-mode prompt.
- **targetSdk=28**: needed for W^X, but skips newer Android security behaviors — acceptable because the attack surface is a loopback server plus local files.

## License

MIT — see [LICENSE](LICENSE)
