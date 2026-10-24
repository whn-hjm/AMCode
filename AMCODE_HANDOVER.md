# AMCode 交接与开发维护文档

> 交接日期：2026-10-24（Asia/Shanghai）  
> 当前代码基线：`ef2b56c`（本地 `main`；代码 commit 4 个尚未 push，本文档为 docs-only 追加）  
> 工作区：`D:\Code\code-server\vsboxed`  
> 远程：`https://github.com/whn-hjm/AMCode.git`  
> 适用对象：zcode / 后续维护者

---

## 0. 给接手模型的提示词（简短版）

```text
你是 AMCode 的接手开发者。工作区：D:\Code\code-server\vsboxed，代码基线 ef2b56c（4 个代码 commit 未 push，另有交接文档 docs commit）。

先完整阅读 AMCODE_HANDOVER.md（本文件），再读 AMCODE_FIXES_2026-10-24.md 和 AMCODE_NEXT_BATCH_PLAN.md。不要重新审计/重做已经修好的 pkg、internal HOME、auth、offline dpkg；只从“未完成任务”里按优先级做增量。

约束：
- 只保留平板 emulator-5558（API34，1600×1200，4:3）；API28 是 headless 5560，测试时才开；不要启动手机 AVD AMCode_Pixel6_API34 / 5556。
- 不要清 5558 的 app 数据；需要 fresh 测试时先说明并用 offline bundle。
- 构建默认 APK 与 offline bundle 时注意 Gradle 会清理 build/outputs 里的额外文件，成品要复制到 D:\ 保存。
- 改代码前先 git status；每次构建后更新 AMCODE_HANDOVER.md 的时间线/验证状态。

下一优先级：release fresh 安装回归 → internal workspace Trust 一键提示 → API 26/29 回归 → watcherExclude → README/ARCHITECTURE 更新 → versionCode bump + push。
```

---

## 1. 项目是什么

AMCode（包名 `com.devbox`，仓库目录名 `vsboxed`）把 **Termux 原生用户态 + code-server + Android WebView** 打成一个 APK。打开 App 即进入 VS Code，不需要 Termux App、不需要 root、不需要 proot。

- 包名必须正好 10 字符：`com.devbox` 与 `com.termux` 等长，bootstrap 二进制/脚本里的路径按同长度替换。
- `minSdk=26`，`targetSdk=28`（刻意保留，targetSdk 29+ 会触发 W^X，不能从 app data 执行二进制）。
- `compileSdk=34`，AGP 8.4.0，Gradle 8.7，JDK 21。
- code-server **4.143.1**（TUR 包），Node **24.18.0**，bootstrap 来自 Termux `bootstrap-2026.08.02-r1+apt.android-7`。
- 版本：`versionName=1.0.0`，`versionCode=1`（尚未 bump）。

---

## 2. 当前架构

```text
MainActivity
  ├─ HomePrefs（internal / external / 迁移提示）
  ├─ 存储权限（API30+ MANAGE_EXTERNAL_STORAGE / API26-29 READ+WRITE）
  ├─ CodeServerService（前台服务，初始化/健康检查/stop）
  └─ WebView
        ├─ http://127.0.0.1:<动态端口>/?folder=<HOME>
        ├─ CookieManager 注入 code-server-session
        ├─ focus/IME inset/JS focus shim
        └─ Trust/Restricted Mode 提示

CodeServerService
  ├─ TermuxManager.initialize()
  │    ├─ bootstrap 解压 / SYMLINKS / keyring / TUR key
  │    ├─ pkg/dpkg wrapper / dpkg DB 迁移
  │    ├─ ensureLibCpp30()
  │    ├─ ensurePkgSetup()：marker 快路径 / full repair
  │    └─ 在线 apt 或 offline bundle 安装 code-server/node
  └─ CodeServerLauncher
       ├─ 127.0.0.1 + ServerSocket(0) 动态端口
       ├─ 随机 token → SHA-256 → cache/run/code-server.yaml (0600)
       ├─ --auth password --config ...
       ├─ POST /login + /mint-key 验证，cookie 交给 Activity
       ├─ PID 文件 + 旧进程清理 / 进程树 kill
       ├─ ripgrep symlink 修复
       └─ android-as-linux.js + execArgv shim
```

### 关键文件

| 文件 | 作用 |
|---|---|
| `App.kt` | `HomePrefs`：HOME 模式、internal/external 迁移、`~/storage/shared` 链接 |
| `TermuxManager.kt` | bootstrap、pkg/apt/dpkg、keyring、SYMLINKS、libc++30、启动 marker、offline bundle 安装 |
| `CodeServerService.kt` | 前台服务、单次 init、健康检查、stop/onTaskRemoved |
| `CodeServerLauncher.kt` | code-server 进程、动态端口、auth、PID/孤儿清理、ripgrep/shim |
| `MainActivity.kt` | 启动状态机、存储、WebView、auth cookie、focus/IME、Trust/Storage UI |
| `WebViewInterface.kt` | 已不再注册 JS bridge；保留但不要重新暴露 |
| `AndroidManifest.xml` | `requestLegacyExternalStorage`、`stopWithTask`、service/notification |
| `assets/amcode-dpkg-wrapper.sh` | apt 调 dpkg 时改写 `.deb` 的 com.termux/com.devbox 路径 |
| `assets/amcode-pkg-setup.sh` | keyring/SYMLINKS/pkg/dpkg DB 修复 |
| `assets/tur.gpg` | TUR 公钥 |
| `assets/android-as-linux.js` | platform shim，execArgv 注入 |

---

## 3. 项目时间线

### 3.1 原始开发（2026-08）

| 时间 | 事件 |
|---|---|
| 2026-08-06 | 项目起步：确定 Termux + bionic + code-server + WebView 架构，不 proot、不 Flutter |
| 2026-08-07 22:00 | `58e78b1` AMCode v1.0 - VS Code on Android |
| 2026-08-07 22:09 | `6d3a6d1` release signing + lint suppression + release APK |
| 2026-08-07 22:38–23:37 | `396d441`/`3fcdbd7`/`88f0024`/`95b4095`/`65eb900`/`fc57544`/`d2da84e`：seccomp/GPG 过滤、retry、镜像测速、README、ARCHITECTURE/DEVELOPMENT |
| 2026-08-08 11:41–12:53 | `e817253`/`736a470`：NODE_OPTIONS、LD_PRELOAD platform 实验 |
| 2026-08-08 15:12–15:31 | `13c0f0f` 回退 VHEditor 原生实验；`d0349a8` mirrors 排除；`2d9b486` termux-keyring 修复 |

### 3.2 本次审计与三轮修复（2026-10-24）

| 时间 | 事件 |
|---|---|
| 2026-10-24 白天 | 全量审计：Android 层、Termux/apt、上游对照、构建层 4 个子代理；产出 `AMCODE_REVIEW_2026-10-24.md` |
| 21:15 | 横屏平板 AVD `AMCode_Tablet_API34` + `run-emulator.bat`；手机 AVD 停用 |
| 21:57 | `6f44555` 第一轮：pkg/keyring/TUR key、dpkg wrapper、启动单次化/完成 marker、Service/WebView 安全、存储权限、动态端口 |
| 22:34 | `a9971e9` 第二轮：WebView focus/IME、ripgrep、android-as-linux shim、libc++30、TUR 并行 Range、offline bundle |
| 22:40 | `1abedd7` 文档：第二轮验证 |
| 23:44 | `ef2b56c` 第三轮：internal HOME + `~/storage/shared`、auth token/cookie、PID/孤儿清理、Trust 提示、`ensurePkgSetup` 快路径、offline dpkg 修复 |
| 23:50 | 本文档；双 APK 产出；API28 headless 测试完成并关闭 |

> 注意：`main` 当前有 **4 个代码 commit 尚未 push**（`6f44555`、`a9971e9`、`1abedd7`、`ef2b56c`），另有本文档 docs commit。push 前先处理 release keystore/密码。

---

## 4. 当前已验证行为（基线）

### 4.1 安装与 pkg

- `pkg install clang` / `hello` / `tree` 可安装；`dpkg --audit` 空（仅 `.dpkg.cfg` 权限 warning）。
- TUR/main 仓库均为签名源；TUR key `6A5C550C260667D1`，main key `5A897D96E57CF20C`。
- dpkg wrapper 会把 `.deb` 的 `data/data/com.termux` 改写为 `data/data/com.devbox`，并处理文本/ELF/symlink。
- 第一轮修复后，`pkg install clang` 的 NO_PUBKEY / dpkg prefix 问题已关闭。

### 4.2 启动

- 首次 fresh offline：`ensurePkgSetup full repair done in 21971ms ... marker written`（含 19 包 postinst）。
- 后续启动：`ensurePkgSetup fast path ... in 122ms`。
- 启动链路：internal HOME → code-server auth → WebView cookie → page loaded；不再双初始化。
- `ensureLibCpp30()` 已把 libc++ 29 升到 30；spdlog/@parcel/watcher 错误消失。

### 4.3 存储

- 新安装默认 `HOME=/data/user/0/com.devbox/files/home`。
- `~/storage/shared -> /storage/emulated/0`；`/sdcard/AMCode` 原文件不搬不删。
- 已有 `home_mode=external` 升级会弹一次迁移对话框（Switch/Keep），不自动移动文件。
- `/storage/emulated` 是 FUSE `noexec`；外部工作区只适合作为共享目录，不适合执行脚本/Git 二进制。
- API28 grant/deny/internal fallback 已通过；API28 fresh 也能到 code-server。

### 4.4 auth

- code-server 启动参数：`--auth password --config cache/run/code-server.yaml --bind-addr 127.0.0.1:<port>`。
- 每次启动随机 32 字节 token；配置里只存 SHA-256 hex（0600）；不写 argv/env，不打印明文。
- 未认证：`GET / -> 302 ./login`，`POST /mint-key -> 401`，WebSocket upgrade -> 401。
- 正确 cookie：`/mint-key -> 200`；WebView 在首次导航前注入 `code-server-session`，无登录页。
- 使用 SHA-256 hashed-password 的原因：明文 password 的 argon2 每请求约 130–160ms，SHA-256 约 3–6ms；4.143.1 支持该 legacy config 路径。

### 4.5 focus / IME

- 根因：启动日志 `terminal_output` TextView 抢走 Android focus/IME served view；`showSoftInput()` 被系统丢弃。
- 修复：overlay 子树不可聚焦；WebView `focusableInTouchMode`；`ensureWebViewFocus()`；IME bottom inset 转为 WebView bottom margin；JS focus shim。
- 实测：终端/扩展搜索一次点击 `mInputShown=true`，WebView `0,0-1600,551`；Back 恢复 1200。

### 4.6 扩展

- 13 个 `@vscode/ripgrep-universal/bin/*/rg` 全部重链到 `$PREFIX/bin/rg`；`rg 15.2.0`。
- `android-as-linux.js` 默认启用；主进程把 `--require` 追加到 `process.execArgv`，扩展宿主看到 linux；fileWatcher/ptyHost/terminal 保持 android。
- 纯 JS 扩展/语言包可安装激活；glibc native 扩展仍不可用。
- Kill switch：`$HOME/.amcode/disable-platform-shim` 或 `AMCODE_ANDROID_PLATFORM_SHIM=0`。

### 4.7 孤儿进程

- PID 文件：`files/run/code-server.pid`（pid/starttime/port/bin，0600）。
- 启动前按 PID + `/proc/<pid>/cmdline` 特征清理旧 code-server；Stop 发 SIGTERM→SIGKILL 进程树；`stopWithTask=true`。
- force-stop 后下次启动会清理旧进程；划任务会停 server（no-orphan 的行为代价）。

### 4.8 双 APK

| 产物 | 大小 | 用途 |
|---|---|---|
| `D:\amcode-release-online.apk` / `app/build/outputs/apk/release/app-release.apk` | 64.52MB | 在线版，首次下载 code-server/node |
| `D:\amcode-release-offline-arm64.apk` | 185.70MB | arm64 离线版，包含 code-server/node，首次只拉主镜像小依赖 |
| `D:\amcode-debug-online.apk` / `app/build/outputs/apk/debug/app-debug.apk` | 68.78MB | debug 在线，当前 5558 已安装 |
| `D:\amcode-debug-offline-x86_64.apk` | 189.62MB | x86_64 debug，offline 测试用 |

offline arm64 APK 已确认包含：

```text
assets/offline-debs/aarch64/code-server_4.143.1_aarch64.deb
assets/offline-debs/aarch64/nodejs-24_24.18.0_aarch64.deb
assets/offline-debs/aarch64/manifest.json
```

在线 release 不含 `offline-debs`。

---

## 5. 构建与运行

### 5.1 环境

- Windows + PowerShell，JDK 21
- Android SDK：`D:\Android\Sdk`
- Gradle wrapper：仓库自带 `gradlew.bat`（Gradle 8.7）
- AVD：
  - `AMCode_Tablet_API34`（可见平板，端口 5558，1600×1200，4:3；当前唯一运行）
  - `AMCode_Tablet_API28`（headless，端口 5560；API28 测试用，当前已关闭）
  - `AMCode_Pixel6_API34`（旧手机 AVD；**不要启动**）
- AVD 默认位于 `C:\Users\15495\.android\avd\`，data 配置各 8GB，实际占用按快照增长；C 盘空间紧张时优先清理不用的 AVD/快照。

### 5.2 构建命令

```powershell
# 默认在线 debug
.\gradlew.bat clean :app:assembleDebug

# 默认在线 release
.\gradlew.bat :app:assembleRelease

# x86_64 offline debug（模拟器测试）
.\gradlew.bat :app:assembleDebug `
  -PofflineBundle=true -PofflineBundleArch=x86_64 `
  -PofflineBundleSourceDir=D:\amcode-debs

# arm64 offline release（手机发布）
.\gradlew.bat :app:assembleRelease `
  -PofflineBundle=true -PofflineBundleArch=aarch64 `
  -PofflineBundleSourceDir=D:\amcode-debs-arm64
```

**重要构建坑**：

- Gradle 构建会清理/覆盖 `app/build/outputs` 里的额外 APK。offline release 构建后如果还要构建 online release，先把 offline APK 复制到 `D:\` 或 repo 外，否则 `app-release-offline-arm64.apk` 会消失。
- 默认构建不会带 offline assets；如果从 offline 构建切回默认，建议 `clean`，并检查 APK 内没有 `offline-debs`。
- offline debs 目录：`D:\amcode-debs`（x86_64）、`D:\amcode-debs-arm64`（aarch64）。
- 构建机 TUR 慢时可用代理先下载两个 deb，再用 `-PofflineBundleSourceDir`；Gradle 会做 SHA256 校验。
- `app-release-offline-arm64.apk` 的 `app-release.jks` 和密码目前都在仓库/构建脚本中；**公开分发前必须轮换 keystore 并把密码移出仓库**。

### 5.3 启动模拟器

```powershell
# 可见平板
D:\Android\Sdk\emulator\emulator.exe -avd AMCode_Tablet_API34 `
  -gpu auto -no-audio -port 5558

# API28 headless（测试时才开）
D:\Android\Sdk\emulator\emulator.exe -avd AMCode_Tablet_API28 `
  -port 5560 -no-window -no-audio -no-snapshot -no-boot-anim `
  -gpu swiftshader_indirect
```

`run-emulator.bat` 默认启动 5558 平板。桌面快捷方式 `AMCode Android Emulator` 指向它。

### 5.4 安装 / 日志

```powershell
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5558 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5558 shell pm grant com.devbox android.permission.POST_NOTIFICATIONS
& $adb -s emulator-5558 shell appops set com.devbox MANAGE_EXTERNAL_STORAGE allow
& $adb -s emulator-5558 logcat -s TermuxManager:I CodeServerLauncher:I CodeServerService:I MainActivity:I
```

### 5.5 关键验证

```powershell
# 启动快路径
adb -s emulator-5558 logcat -d -s TermuxManager:I |
  Select-String 'ensurePkgSetup (fast path|full repair)'

# auth：从 pid 文件拿端口，从 yaml 拿 hash
adb -s emulator-5558 shell run-as com.devbox cat files/run/code-server.pid
adb -s emulator-5558 shell run-as com.devbox cat cache/run/code-server.yaml
adb -s emulator-5558 forward tcp:<PORT> tcp:<PORT>
curl.exe -i http://127.0.0.1:<PORT>/                    # 302 ./login
curl.exe -i -X POST http://127.0.0.1:<PORT>/mint-key   # 401
# 带 Cookie: code-server-session=<hash> 再请求 -> 200
```

```bash
# pkg / db / rg
PREFIX=/data/user/0/com.devbox/files/usr
export PATH=$PREFIX/bin:/system/bin HOME=/data/user/0/com.devbox/files/home
pkg install -y hello
dpkg --audit
dpkg-query -W code-server nodejs-24
rg --version
```

---

## 6. 已知问题与边界（接手先看）

1. **internal workspace 仍显示 VS Code Restricted Mode banner**：首次需要点 Trust，用户扩展才启用。当前按“提示、不自动信任”的产品决定保留；如果要改善，可加一次 App 级 Trust 引导或对 app-owned internal HOME 做受控预信任。
2. **API28 `py3compile Bad system call`**：python postinst 的 py3compile 在 API28 seccomp 下失败，但 `dpkg exit=0`、`dpkg --audit` 空、code-server 正常；Python 字节码预编译可能不完整。
3. **旧安装的 code-server/node 不会自动补登记到新 dpkg DB**：旧流程丢弃过嵌套 DB；fresh/offline 安装已正确登记。需要的话可做一次性重装/登记迁移。
4. **没有“切回 external”UI**：existing external 保留；Storage action 提供切 internal/授权共享存储，但没有反向切换。
5. **`versionCode=1`**：正式发版必须 bump；release 尚未 fresh 安装做 UI 回归（debug 已完整验证）。
6. **默认在线 TUR 仍慢**：TUR 没有可用镜像，8 连接 Range 受 GitHub 限速；offline 版才是无 TUR 方案（185MB）。
7. **auth 使用 SHA-256 hashed-password**：code-server 4.143.1 的 legacy 路径；未来升级 code-server 必须重新验证登录/cookie。
8. **glibc native 扩展仍不可用**：bionic 无 libstdc++.so.6/glibc；纯 JS/语言包可用。要完整原生扩展需 proot/glibc（CodeFA 路线）或 VHEditor 式 patched code-server。
9. **`~/storage/shared` watcher**：链接在 workspace 根下，VS Code file watcher 可能跟随；建议加 `files.watcherExclude`。
10. **release keystore/密码在仓库中**：公开前轮换/移出；`amcode-release.jks` 仍在 `app/`。
11. **README/ARCHITECTURE 已过时**：README 仍写首次 236MB/默认 `/sdcard/AMCode`；ARCHITECTURE 仍写 localhost:8080/无 auth。需要更新。
12. **`AMCODE_REVIEW`/`AMCODE_NEXT_BATCH_PLAN`/`review-assets`**：review assets 是本地证据，未全部 commit；接手时直接读 workspace 文件。
13. **未 push**：本地 `main` 有 4 个代码 commit（外加本文档）未推送；推送前确认 release keystore/密码问题。
14. **C 盘 AVD 占用**：两个 8GB data 配置的 AVD 默认在 C 盘；测试环境清理时不要误删 5558 需要的 API34 AVD。

---

## 7. 计划了但还没完成的任务

按优先级，均建议在现有基线上增量，不要重做已修部分。

### P0/P1

- [ ] **release fresh 安装回归**：用 `D:\amcode-release-online.apk` 和离线 arm64 在 fresh AVD/数据上安装，验证 release 无 WebView 调试、auth、focus、pkg、internal HOME。
- [ ] **internal workspace Trust 一键提示**：默认 internal 首次打开时给 App 级说明/引导；保留 VS Code 自身 Trust，不自动 `--disable-workspace-trust`。
- [ ] **versionCode bump + push**：发版前把 `versionCode` 从 1 提升；确认 keystore 安全后再 push 4 个本地 commit。
- [ ] **API 26/29 回归**：已有 API28，补 API26/29 的 READ/WRITE、grant/deny、internal fallback、code-server 安装。
- [ ] **README/ARCHITECTURE 更新**：internal HOME、auth、双 APK、offline 安装、API28 支持、旧文档命令。
- [ ] **`files.watcherExclude`**：把 `**/storage/shared/**` 加入默认设置，避免 watcher 跟随 `/sdcard`。
- [ ] **显式 external 切换 UI**：Storage action 增加“切回 /sdcard/AMCode”，并显示 FUSE/noexec 警告。

### P2

- [ ] **旧安装 dpkg DB 迁移**：把旧 code-server/node 补登记，或提供一次性重装；同时验证 `pkg upgrade` 后 marker 失效/全修。
- [ ] **API28 py3compile 兼容**：评估 seccomp/`py3compile` 失败是否影响 Python 扩展；必要时在 postinst 跳过或改用 `python -m compileall`。
- [ ] **在线 TUR 下载体验**：进度 UI、断点续传状态、失败原因展示；连接数/限速调优需真实网络测试。
- [ ] **offline bundle 更新机制**：TUR 版本升级时更新 `build.gradle.kts` 的 pinned URL/SHA256；测试 arm64 release。
- [ ] **auth 升级路径**：若升级 code-server，验证 hashed-password/legacy 是否仍支持，或迁移到受支持的 auth 方式。
- [ ] **Git 安装/扩展体验**：`git` 未安装，Git 相关扩展会报 not found；可把 git 加入初始/推荐安装。
- [ ] **`~/storage/shared` 权限流程**：无 MANAGE 时首次打开共享目录的请求/拒绝/重试 UI 完整回归。
- [ ] **release 签名安全**：轮换 keystore、移出密码、更新 CI/构建说明。

### 长期/架构级

- [ ] **glibc native 扩展**：proot/glibc 用户态（CodeFA）或 VHEditor 式 patched code-server；不是小修。
- [ ] **Microsoft Marketplace**：当前只支持 Open VSX/VSIX，不要承诺官方 Marketplace。
- [ ] **UI/UX**：mobile keyboard toolbar、layout、virtual mouse 等。
- [ ] **CI**：目前无 CI；至少加 build + lint + 双 APK 构建。

---

## 8. 维护约定

- 改代码前 `git status`；不要覆盖未跟踪的 review/截图。
- 每次改动后：
  1. `clean assembleDebug` / 需要时 release；
  2. 5558 安装验证；
  3. 更新本文件时间线和验证状态；
  4. commit（不要提交 `review-assets`，除非明确需要）。
- 不要启动手机 AVD；不要清 5558 数据；fresh 测试用 API28 headless 或明确说明。
- 不要恢复 `--auth none`；不要把 token 写进 argv/env/logcat。
- 不要用 `--root` 跑 offline dpkg；当前是 `--instdir` + configure，改动前先读 `TermuxManager` 的 dpkg 注释。
- 不要移除 `android-as-linux.js` 的 execArgv 注入；只设 NODE_OPTIONS 不会到达扩展宿主。
- 不要重新引入 `terminal_output` 可聚焦；会导致 IME 再次被抢。
- 构建产物优先复制到 `D:\`，避免 Gradle 清理。

---

## 9. 参考文档

- [AMCODE_FIXES_2026-10-24.md](AMCODE_FIXES_2026-10-24.md)：三轮修复的详细根因、日志、验证。
- [AMCODE_REVIEW_2026-10-24.md](AMCODE_REVIEW_2026-10-24.md)：全量审计报告（P0/P1/P2）。
- [AMCODE_NEXT_BATCH_PLAN.md](AMCODE_NEXT_BATCH_PLAN.md)：下一批计划（已执行 A/B/C，残余见本文第 7 节）。
- `ARCHITECTURE.md` / `DEVELOPMENT.md` / `README.md`：历史文档，部分已过时。
- `review-assets/`：验证截图/日志（本地证据）。

---

*交接完成。后续以本文件 + git log 为准；如果文档与代码冲突，先以当前代码和可复现实测为准，再更新文档。*
