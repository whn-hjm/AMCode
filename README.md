# AMCode — VS Code on Android

<p align="center">
  <strong>One APK. Open it and you're in VS Code. No Termux, no root.</strong>
</p>

<p align="center">English | <a href="README_zh.md">简体中文</a></p>

<p align="center">
  v1.1.0 (versionCode 2) · code-server 4.143.1 · Node.js 24.18.0 · Android 8.0+
</p>

---

AMCode bundles [code-server](https://github.com/coder/code-server) with a Termux-native runtime into a single Android app. Open it and you're staring at a fully functional VS Code editor — write, compile, debug, all on your phone or tablet.

Big thanks to **[Termux](https://github.com/termux/termux-app)** for the ARM64/x86_64-native Linux environment, and **[code-server](https://github.com/coder/code-server)** for bringing VS Code to the browser. AMCode simply puts these two great projects together in one APK.

## Two APK Variants

| | Online (default) | Offline (arm64) |
|---|---|---|
| APK size | ~64 MB | ~162 MB (arm64-only) |
| code-server + Node.js | downloaded on first launch from TUR | bundled inside the APK |
| First-launch download | ≈127 MB from TUR + ~23 MB dependencies | ~23 MB dependencies from the main mirror only (never touches TUR) |
| git included at first launch | no (`pkg install git`) | **yes** |
| Best for | any network that can reach `tur.kcubeterm.com` | slow/unreachable TUR, reproducible installs |

Both variants are otherwise identical. The offline APK embeds the same pinned, SHA-256-verified `code-server` and `nodejs-24` `.deb`s that the online variant downloads; on first launch it pulls the small dependencies **plus git** from the main Termux repository, so it works as a self-sufficient offline dev environment.

## Installation & First Launch

1. Download the APK from [Releases](https://github.com/whn-hjm/AMCode/releases) and install it.
2. Open AMCode. **No storage permission is requested** — the initial workspace lives in the app-private home directory (`/data/user/0/com.devbox/files/home`).
3. The app unpacks a native bootstrap (Termux `bootstrap-2026.08.02-r1+apt.android-7`, ~32 MB per ABI, bundled in the APK) and installs code-server. You'll see live progress in the loading overlay:
   - **Online variant:** ≈127 MB from TUR — downloaded with 8 parallel HTTP Range connections with resume support — plus ~18 MB of dependencies.
   - **Offline variant:** ~18 MB of dependencies from the main Termux mirror only.
4. Once complete, VS Code appears in the app's WebView.

A persistent notification keeps the server alive. It has two actions: **Stop** and **Storage**.

## Workspace & Storage

- **Default (new installs):** internal home at `/data/user/0/com.devbox/files/home`. Nothing outside the app sandbox is touched, and `~/storage/shared` is a symlink to `/storage/emulated/0` for read access to shared storage.
- **Switch anytime:** the notification's **Storage** action (or the in-app dialog) toggles between the internal home and the legacy shared-storage workspace `/sdcard/AMCode`. Switching never moves any files; each location keeps its own contents.
- **Shared storage is `noexec`:** the external workspace is fine for editing files, but Git hooks, compiled binaries, and Python venvs won't execute from there. The dialog warns about this.
- **Legacy installs:** if you previously ran an older AMCode release with the external workspace, a one-time migration dialog asks whether to move to the internal home (files are left in place either way).

## Terminal & Packages

Press `` Ctrl+` `` for a real **bash** shell running on Android's bionic libc — no proot, no emulation.

`pkg` works just like in Termux (same repositories, thousands of packages). The **offline variant ships with git**; on the online variant install it yourself:

```bash
pkg install git          # online variant only (offline ships with git)
pkg install clang make cmake   # C / C++
pkg install python             # Python
pkg install rust               # Rust
pkg install openjdk-17         # Java
pkg install golang             # Go
```

Notes:

- The main repository is served from the fastest of three tested mirrors (Tsinghua, USTC, official); a mirror must answer HTTP 200 to be eligible.
- All repositories are GPG-signed (main repo key `5A897D96E57CF20C`, TUR key `6A5C550C260667D1`); the keys ship inside the APK. No `trusted=yes` sources remain after setup.
- Installed `.deb`s are repackaged on the fly so Termux's `data/data/com.termux` payload paths land under AMCode's own package directory — apt/dpkg behave normally afterwards.

## Extensions

- The marketplace is **[Open VSX](https://open-vsx.org)** (code-server's default), not the Microsoft Marketplace — Microsoft's marketplace may only be used by official VS Code products.
- **Searching with non-English keywords finds little** (Open VSX's search index matches English text): for language packs search `Simplified` / `zh-hans` / `Japanese`, or download the `.vsix` from open-vsx.org and use *Install from VSIX*.
- Pure-JS extensions and language packs install and run. **glibc-native extension binaries do not work** (the runtime is bionic), so extensions that ship prebuilt `.node` binaries compiled against glibc won't load.
- A platform shim (`android-as-linux.js`) is injected into the extension host so extensions see a Linux-ish platform (`fileWatcher`, `ptyHost`, terminal stay Android-native). Kill switches: create `$HOME/.amcode/disable-platform-shim` or set `AMCODE_ANDROID_PLATFORM_SHIM=0`.
- VS Code **Workspace Trust** is not bypassed. On first load you get a one-time hint explaining trust; restricted mode and the trust prompt behave exactly as upstream. Only trust folders you control.
- `ripgrep` (15.2.0) is re-linked at startup so search works.

## Security

- code-server binds to `127.0.0.1` on a **dynamically allocated port** (chosen by binding an ephemeral socket before launch) with `--auth password`.
- A random 32-byte token is generated on **every** start. Only its SHA-256 hex digest is written to a `0600` config file (`cache/run/code-server.yaml`). The token never appears in argv, environment variables, or logcat (stdout monitoring redacts it).
- The app itself logs in (`POST /login`, verified via `/mint-key`) and injects the resulting **HttpOnly** session cookie into the WebView. The page never handles the raw password.
- The server is unreachable from other devices: loopback-only bind, and no JavaScript↔native bridge is registered in the WebView.
- A PID file plus `/proc` validation cleans up orphaned code-server processes from previous runs; the service stops the whole tree on task removal and app stop.
- WebView debugging is enabled only for debuggable (non-release) builds.
- Health check every 10 s; automatic restart on crash, at most 10 consecutive attempts.

## Requirements

| | |
|---|---|
| **Android** | 8.0+ (API 26+) |
| **Architecture** | ARM64 (most devices) or x86_64 (emulator) |
| **Storage** | ~1.5 GB free (environment + packages + workspace) |
| **Network** | First launch only (online variant: TUR must be reachable — see below). Fully offline afterwards. |

TUR (`tur.kcubeterm.com`) has no mirrors and can be slow in some regions. If the ≈127 MB TUR download is painful, use the offline APK or a proxy/VPN.

## Build

```bash
# Prerequisites: JDK 21, Android SDK 34 (AGP 8.4.0, Gradle 8.7 — wrapper included,
# with distribution SHA-256 verification)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleRelease                      # online APK, universal (~64 MB)
./gradlew assembleRelease -PofflineBundle=true -PofflineBundleArch=aarch64 \
    -PbootstrapArch=aarch64                    # offline APK, arm64-only (~162 MB)
# -PofflineBundleArch=x86_64 for the emulator; -PofflineBundleSourceDir=/path/to/debs
# to reuse previously downloaded .debs (filename must match).
# -PbootstrapArch=aarch64|x86_64 stages a single bootstrap zip (~33 MB smaller
# APK); omit it for a universal APK that carries both.
# APK at: app/build/outputs/apk/release/app-release.apk
```

- **Bootstrap:** the Termux bootstrap archives (aarch64 32,203,582 B / x86_64 32,112,380 B) are downloaded automatically during the first build (cached in `bootstrap-cache/`), pinned by SHA-256; a missing or corrupt archive fails the build with manual-download instructions. `-PbootstrapArch=<abi>` ships only one of them.
- **Signing:** put `app/keystore.properties` (gitignored) with `storeFile`/`storePassword`/`keyAlias`/`keyPassword`, or export `AMCODE_STORE_FILE` / `AMCODE_STORE_PASSWORD` / `AMCODE_KEY_ALIAS` / `AMCODE_KEY_PASSWORD`. A partially filled configuration fails the build (rather than silently producing an unsigned APK); with nothing configured you get an unsigned APK.
- The dependency set is deliberately minimal (no Material, no webkit-v1, no lifecycle-service); R8 shrinking is enabled for release.

## Limitations

- **No Microsoft Marketplace** — extensions come from Open VSX only (licensing).
- **No glibc-native extensions** — bionic runtime; prebuilt `.node` binaries from glibc builds fail to load.
- **Shared storage is `noexec`** — Git hooks, compiled artifacts, and virtualenvs must live in the internal home to run.
- **`targetSdk = 28`** — required so app-data binaries can execute (W^X policy on API 29+). This is the same trade-off Termux makes, and it means the app cannot ship on Google Play.
- **TUR can be slow** (no mirrors) — prefer the offline APK or a proxy.
- A known Termux quirk is tolerated: `py3compile` dies on API 28 under seccomp; dpkg treats it as non-fatal and the package self-heals via `dpkg --configure -a` at next startup.

## Acknowledgements & License

- [Termux](https://github.com/termux/termux-app) — the native Linux environment, bootstrap, and package repositories.
- [code-server](https://github.com/coder/code-server) — VS Code in the browser.
- [TUR (Termux User Repository)](https://github.com/termux-user-repository/tur) — code-server and Node.js packages.

MIT — see [LICENSE](LICENSE).

---

AMCode is not affiliated with Microsoft, the VS Code project, the Termux project, or Open VSX.
