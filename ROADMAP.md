# AMCode Roadmap

## v1.0 — Current (Debug)

- [x] Termux bootstrap extraction + binary patch
- [x] TUR repo code-server 4.131.0 via apt
- [x] WebView renders VS Code at localhost:8080
- [x] Foreground Service keep-alive + crash recovery
- [x] Live terminal output during installation
- [x] Retry logic for failed package installs
- [x] External home directory (/sdcard/AMCode) with permission dialog
- [x] App icon + AMCode branding
- [x] Chinese/English permission dialog
- [ ] Release signing + GitHub release

## v1.1 — Sweeteners

- [ ] **Dark theme follow system** — loading overlay matches system dark/light
- [ ] **Keyboard shortcuts** — intercept Ctrl+S/Z/C/V etc. in WebView
- [ ] **File picker** — SAF-based "Open Folder" to switch workspace
- [ ] **First-launch size hint** — show "~236MB will be downloaded" before starting
- [ ] **Notification actions** — "Stop Server" / "Restart Server" from notification bar
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
- [ ] **Offline package cache** — pre-download common deps into APK (optional 200MB+ bundle)

## Ideas (unscheduled)

- [ ] Widget — home screen shortcut to start/stop server
- [ ] Termux package backup — export `dpkg --get-selections` for restore
- [ ] CPU/memory monitor in notification
- [ ] Battery-aware — pause health checks when battery saver is on
