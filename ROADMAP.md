# AMCode Roadmap

> **Archived.** This file is no longer maintained. It has been superseded by
> [AMCODE_ROADMAP.md](AMCODE_ROADMAP.md) (heavy-task pool) and §7 of
> [AMCODE_HANDOVER.md](AMCODE_HANDOVER.md) (mainline tasks). The content below
> is kept for historical reference only — it reflects planning state as of
> v1.0 and does not match the shipped v1.1.0 (versionCode 2) codebase. Note
> the section names below are *planning* milestones: shipped v1.1.0 covers the
> planning-v1.0 list plus individual items from later sections (marked inline).

## v1.0 — Current (Debug) — shipped as v1.1.0 (versionCode 2)

- [x] Termux bootstrap extraction + binary patch
- [x] TUR repo code-server via apt (now pinned at 4.143.1 + Node.js 24.18.0)
- [x] WebView renders VS Code at a loopback dynamic port (no fixed 8080; auth password + cookie injection)
- [x] Foreground Service keep-alive + crash recovery
- [x] Live terminal output during installation
- [x] Retry logic for failed package installs
- [x] External home directory (/sdcard/AMCode) with permission dialog (now the opt-in legacy choice; internal HOME is the default)
- [x] App icon + AMCode branding
- [x] Chinese/English permission dialog
- [x] Release signing + GitHub release (signing via app/keystore.properties or AMCODE_* env vars is implemented; unsigned APK if unconfigured)

## v1.1 — Sweeteners

- [ ] **Dark theme follow system** — loading overlay matches system dark/light
- [ ] **Keyboard shortcuts** — intercept Ctrl+S/Z/C/V etc. in WebView
- [ ] **File picker** — SAF-based "Open Folder" to switch workspace
- [ ] **First-launch size hint** — show "~236MB will be downloaded" before starting
- [ ] **Notification actions** — ~~"Stop Server" / "Restart Server" from notification bar~~ done in v1.1.0 as **Stop** + **Storage** actions (restart exists as an internal service action)
- [ ] **Long-press back = minimize** — keep code-server running in background

## v1.2 — Extensions & Tools

- [ ] **Pre-install popular extensions** — Python, Go, Rust language packs
- [ ] **Git sign-in helper** — prompt for username/email on first commit
- [ ] **Status bar in loading overlay** — show "2 packages remaining" etc.

## v2.0 — Polish

- [ ] **Settings page** — port, auth password, extensions dir, workspace path
- [ ] **Auto-update** — check GitHub releases, download new APK
- [ ] **Import/export** — backup settings + installed packages
- [ ] **Multi-window support** — split screen on tablets
- [ ] **Screensaver mode** — dim screen but keep server alive during long compiles

## v2.5 — Power

- [ ] **Multiple workspaces** — tab switching between projects
- [ ] **SSH remote** — expose code-server over WiFi with QR code pairing
- [ ] **Offline package cache** — ~~pre-download common deps into APK (optional 200MB+ bundle)~~ done: `-PofflineBundle=true -PofflineBundleArch=aarch64|x86_64` bundles pinned code-server+nodejs debs (arm64 offline APK 185.7 MB; first launch pulls only ~18 MB of deps, never touches TUR)

## Ideas (unscheduled)

- [ ] Widget — home screen shortcut to start/stop server
- [ ] Termux package backup — export `dpkg --get-selections` for restore
- [ ] CPU/memory monitor in notification
- [ ] Battery-aware — pause health checks when battery saver is on
