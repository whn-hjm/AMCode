# AMCode 本轮修复记录（2026-10-24）

> 基线：`2d9b486`（原评测报告见 [AMCODE_REVIEW_2026-10-24.md](AMCODE_REVIEW_2026-10-24.md)）  
> 本轮目标：先把用户实际遇到的 `pkg install clang` 修好，同时修掉启动/权限/Service/WebView 的 P0/P1，并换成横屏平板模拟器。  
> 当前状态：**debug APK 已构建并安装在平板 AVD `emulator-5558` 上；release APK 已构建但未安装。改动未 commit。**

---

## 1. 结果摘要

| 问题 | 状态 | 证据 |
|---|---|---|
| `pkg install clang` 报 NO_PUBKEY / unsigned repo | **已修复** | 主仓 + TUR 均签名通过；`pkg install -y clang` exit 0；`clang 21.1.8` 可编译运行 |
| `pkg install hello/tree` 因 dpkg 写 `/data/data/com.termux` 失败 | **已修复** | wrapper 改写 `.deb` 路径；hello/tree/clang 均落到 `$PREFIX`，`dpkg --audit` 空 |
| 首启双初始化、apt 抢锁 | **已修复** | 最终日志只有一次 `Initialization HOME=`、一次 `ensurePkgSetup`、一次 code-server |
| 失败后 `isReady()` 永久 brick | **已修复** | `.amcode-ready` 完成标记；`isReady()` 同时要求 code-server；已安装环境重启不再报 `code-server not installed` |
| code-server 监听 `0.0.0.0` | **已修复（部分）** | `--bind-addr 127.0.0.1:<动态端口>`；`--auth none` 仍未改，同机其他 app 仍可访问 loopback |
| Android 8–10 存储权限未申请 | **已修代码，未测旧系统** | API 26–29 已走 `RequestMultiplePermissions`；失败回退内部 HOME |
| HOME 选择不持久化 / START_STICKY 用错目录 | **已修复** | `HomePrefs` SharedPreferences；日志 `persisted home mode=external` |
| Stop 不停 Service / 主线程 waitFor / 固定 8080 | **已修代码** | ACTION_STOP 取消 init/health + 异步 stop + stopSelf；动态端口 40457/33847 |
| WebView 调试常开 / URL 前缀绕过 / JS bridge 过宽 | **已修复** | debug 用 `FLAG_DEBUGGABLE`，release 关闭；精确 host+port；bridge 不再注册 |
| 平板比例不适合 code-server | **已解决** | `AMCode_Tablet_API34`，1600×1200、4:3 横屏 |

---

## 2. 代码改动

`git diff --stat`（7 个文件，+1384/−289）：

```text
 AndroidManifest.xml                   |   7 +-
 App.kt                                | 120 ++++
 CodeServerLauncher.kt                 | 281 ++++++--
 CodeServerService.kt                  | 246 +++++--
 MainActivity.kt                       | 793 ++++++++++++++++-----
 TermuxManager.kt                      | 169 ++++-
 WebViewInterface.kt                   |  57 +-
```

新增 assets：

- `app/src/main/assets/amcode-dpkg-wrapper.sh`
- `app/src/main/assets/amcode-pkg-setup.sh`
- `app/src/main/assets/tur.gpg`（TUR 公钥，fingerprint `43EEC3A2934343315717FF6F6A5C550C260667D1`）

### 2.1 pkg / apt / dpkg

- **GPG**：`trusted.gpg.d` 原先是坏掉的绝对 symlink（指向 `/data/data/com.termux/...`）。改为复制 `share/termux-keyring/*.gpg` 为普通文件；额外打包 TUR key，`pkg` 不再依赖 `trusted=yes`。主仓 key：`termux-autobuilds.gpg`。
- **SYMLINKS**：`amcode-pkg-setup.sh` 解析 bootstrap `SYMLINKS.txt`，补齐约 1092 个缺失链接（含 `libtermux-exec.so`），保留已有 1163 个。
- **pkg 源/缓存**：修复 `bin/pkg` 的 `/data/data/com.termux/cache/apt` 缓存路径；TUR 源从 `trusted=yes` 升级为签名源；主仓在 dpkg DB 建立后才移除 bootstrap 的 trusted fallback。
- **dpkg prefix**：apt `Dir::Bin::dpkg` 指向 `$PREFIX/libexec/amcode/dpkg`。wrapper 在 `--unpack`/`--recursive` 时把 `.deb` 的 `data/data/com.termux` 改写为 `data/data/com.devbox`，同步改写文本/ELF/绝对 symlink，重建 `.deb`；带 `--root` 的初始安装直接透传。
- **DB 迁移**：`status`、`status-old`、`info/*`、`alternatives/*` 里的 `com.termux` 路径迁移为 `com.devbox`，备份 `status.amcode-bak`。
- **termux-exec**：环境变量增加 `TERMUX__PREFIX` / `TERMUX_PREFIX`；`LD_PRELOAD` 指向 `libtermux-exec.so`。实测 `#!/usr/bin/env sh` 在带这些变量时可执行。

### 2.2 启动 / 权限 / Service / WebView

- **单次启动**：`MainActivity` 的 `startupStarted` / `storageResolved` / `serviceStartRequested`；`beginStartup()` 是唯一入口，先存 HOME，再处理通知权限，最后只 start 一次。
- **存储**：
  - API 30+：`MANAGE_EXTERNAL_STORAGE` 设置页，带 `ActivityNotFoundException` fallback。
  - API 26–29：`RequestMultiplePermissions` 请求 READ/WRITE，拒绝或不可写回退内部 HOME。
  - `HomePrefs` 持久化，Service 在 code-server 启动前恢复；`isExternalWritable()` 做实际写 probe。
- **Service**：`initJob` 单飞；`ACTION_STOP` 取消 init/health、异步 stop、`stopForeground(REMOVE)`、`stopSelf`；`onDestroy` 不再主线程 waitFor；`restartCount` 成功后归零。
- **code-server**：`127.0.0.1` + `ServerSocket(0)` 动态端口；健康检查校验进程存活 + 端口；`generation` 防止旧进程状态覆盖新进程。
- **WebView**：`setWebContentsDebuggingEnabled(isDebuggableBuild())`；精确 `Uri.host/port`；不注册 JS bridge；`allowFileAccess=false` / `allowContentAccess=false` / 不开 universal file access；`onReceivedError` 新签名只处理主框架，带 3 次自动重试 + 可点击 Retry；`onRenderProcessGone` 重建 WebView。
- **完成标记**：`TermuxManager` 写 `files/.amcode-ready`；`isReady()` 要求 marker + bash/apt/lib + `bin/code-server`；`tryInstall()` 改用 `isBootstrapReady()`。这样半安装不会被误判为 ready。

---

## 3. 实测结果

### 3.1 最终 debug APK（平板 emulator-5558）

安装覆盖测试环境后启动日志：

```text
MainActivity: Startup: persisted home mode=external
MainActivity: HOME set to external storage: /storage/emulated/0/AMCode
MainActivity: CodeServerService start+bind requested
CodeServerService: Initialization HOME=/storage/emulated/0/AMCode
TermuxManager: [amcode-pkg] keyring: 10 key files installed / TUR key present
TermuxManager: [amcode-pkg] symlinks: created=0 kept=1163 target-missing=0
TermuxManager: [amcode-pkg] dpkg db: migrated_files=0
TermuxManager: ensurePkgSetup done: exit=0 assets=3 warnings=0
TermuxManager: Termux environment already initialized
CodeServerLauncher: code-server PID=4624 bound to 127.0.0.1:40457
MainActivity: code-server ready, loading http://127.0.0.1:40457
MainActivity: WebView page loaded: http://127.0.0.1:40457/?folder=/storage/emulated/0/AMCode
```

- 只有一次初始化（没有双 `=== VSBoxed Environment Setup ===`）。
- 动态端口 40457，只监听 `127.0.0.1`（日志明确）。
- code-server 4.143.1 正常，VS Code 页面加载；截图：[`review-assets/final-tablet4.png`](review-assets/final-tablet4.png)、[`review-assets/final-tablet5.png`](review-assets/final-tablet5.png)。

### 3.2 pkg 实测

在 **emulator-5558** 上（修复后的环境）：

```text
pkg install -y hello  -> hello is already the newest version (2.12.3-1), rc=0
pkg install -y clang  -> clang is already the newest version (21.1.8-3), rc=0
clang --version       -> clang version 21.1.8 / Target: x86_64-unknown-linux-android24
tree --version        -> tree v2.3.2
dpkg --audit          -> 无包错误（仅 $HOME/.dpkg.cfg 权限 warning）
```

A 在修复过程中还完成了真正的首次安装验证：

- `pkg install -y hello` exit 0，`hello` 输出 `Hello, world!`；
- `pkg install -y tree` exit 0；
- `pkg install -y clang` exit 0，安装 clang/llvm/lld/ndk-sysroot 等，并编译运行了一个 C 程序；
- `dpkg --audit` 空；`code-server --version` 仍是 4.143.1；`.amcode-ready` 保留。

这直接覆盖了用户截图里的 `pkg install clang` 场景。

### 3.3 模拟器

- 当前只保留平板 AVD：`AMCode_Tablet_API34`，1600×1200、4:3 横屏、240dpi，端口 5558。
- 手机 AVD `AMCode_Pixel6_API34` 已关闭，且明确要求子代理不得再启动。
- 桌面快捷方式/`run-emulator.bat` 已改为平板。
- 平板主页截图：[`review-assets/tablet-home.png`](review-assets/tablet-home.png)；code-server 截图：[`review-assets/tablet-vscode.png`](review-assets/tablet-vscode.png)。

---

## 4. 仍未解决 / 残余风险

1. **`--auth none` 仍在**：已只 bind `127.0.0.1`，LAN 暴露消除；但 Android loopback 不隔离 app，同机其他有 INTERNET 权限的 app 仍可访问 code-server 终端。要彻底解决需随机 token / 认证代理。
2. **扩展/native 模块**：`ripgrep` 的 13 个绝对链接已修复，libc++30 已安装，`spdlog`/`@parcel/watcher` 现在能加载；但 Linux/glibc native 扩展（如 glibc 版 `watcher.node`）在 bionic 上仍会 `libstdc++.so.6 not found`。纯 JS/语言包/主题可用，Microsoft Marketplace 仍不支持。
3. **`/sdcard` 工作区**：默认仍是外部 `/sdcard/AMCode`；Git/编译产物/noexec 的官方限制仍在（`HomePrefs` 允许内部，但没有 UI 切换）。
4. **键盘 IME**：已修（`terminal_output` 抢 IME + IME inset）；扩展搜索框一次点击可弹出键盘，WebView 缩到 `0,0-1600,551`。终端输入仍需用户实测确认。
5. **孤儿 code-server**：硬杀 app 进程仍可能留下旧进程；动态端口避免误判，但没有 PID 文件/进程树清理。
6. **API 26–29 未实测**：代码已加运行时权限和 fallback，但需要在 API 28/29 模拟器上验证 grant/deny。
7. **启动耗时**：`ensurePkgSetup` 每次启动约 8 秒（扫描 1163 symlink + DB），可后续加版本 marker/轻量检查优化。
8. **release APK 未安装**：release 已构建（64.48MB），debug 已验证；release 签名与 debug 不同，不能直接覆盖安装。
9. **`$HOME/.dpkg.cfg` warning**：`dpkg --audit` 会提示 `/storage/emulated/0/AMCode/.dpkg.cfg` 权限不足；不影响安装，但外部 HOME 的副作用仍在。
10. **Workspace Trust**：打开未信任文件夹时，用户扩展会被 Restricted Mode 禁用，需要点 Trust；未默认加 `--disable-workspace-trust`（安全取舍）。
11. **Git 未安装**：`$PREFIX/bin/git` 不存在，Git 相关扩展会报 “Git installation not found”。

---

## 5. 产物与使用

| 产物 | 路径 |
|---|---|
| debug APK（已安装平板） | `app/build/outputs/apk/debug/app-debug.apk`（68.75MB） |
| release APK（已构建未安装） | `app/build/outputs/apk/release/app-release.apk`（64.48MB） |
| 平板 AVD | `AMCode_Tablet_API34`，adb `emulator-5558` |
| 启动脚本 | [run-emulator.bat](run-emulator.bat) |
| 评测报告 | [AMCODE_REVIEW_2026-10-24.md](AMCODE_REVIEW_2026-10-24.md) |
| 本轮截图 | [review-assets](review-assets) |

常用命令：

```powershell
# 启动平板
D:\Android\Sdk\emulator\emulator.exe -avd AMCode_Tablet_API34 -gpu auto -no-audio -port 5558

# 安装/覆盖 debug
adb -s emulator-5558 install -r app\build\outputs\apk\debug\app-debug.apk

# 看日志
adb -s emulator-5558 logcat -s TermuxManager:I CodeServerService:I CodeServerLauncher:I MainActivity:I

# pkg 验证（在 app 终端或 run-as 脚本里）
pkg install -y clang
clang --version
```

---

## 6. 第二轮修复（2026-10-24 晚）

针对用户反馈的三个问题做了第二批：

### 6.1 WebView 输入框不聚焦

**根因（已实测）**：`loading_overlay` 虽然 GONE，但其中 `textIsSelectable="true"` 的 `terminal_output` TextView 在启动时抢到了 Android focus；`dumpsys input_method` 显示 `mServedView=...terminal_output`，Chromium 的 `showSoftInput()` 被系统以 “view is not served” 丢弃。按钮不需要 IME 所以正常，输入框表现为点很多次才偶尔成功。

**修复**：

- `activity_main.xml`：根布局加 `@id/root`；WebView `focusable/focusableInTouchMode/clickable`；overlay `descendantFocusability=blocksDescendants` 且不可聚焦；日志 TextView/ScrollView 不可聚焦。
- `MainActivity.kt`：`ensureWebViewFocus()` 在 `onPageFinished`/`onWindowFocusChanged`/`onResume`/`ACTION_DOWN` 时把 Android focus 和 IME served view 拉到 WebView；IME bottom inset 转成 WebView bottom margin，键盘弹出时 WebView 从 `0,0-1600,1200` 缩到 `0,0-1600,551`；JS focus shim 仅处理 input/textarea/contenteditable/xterm helper textarea，不抢按钮/链接。
- **实测**：冷启动 `mServedView=WebView`；扩展搜索框一次点击 `mInputShown=true`，键盘出现，WebView 缩到 `0,0-1600,551`，IME frame `[0,551][1600,1200]`；不再出现 “Ignoring showSoftInput ... is not served”。截图：[`review-assets/batch2-focus.png`](review-assets/batch2-focus.png)、[`review-assets/focus-search4.png`](review-assets/focus-search4.png)。
- 注：扩展搜索框一次点击已验证；终端 xterm 使用同一套 WebView focus/IME 修复，建议用户再顺手试一次终端输入。

### 6.2 首次安装网络

**事实**：TUR 没有可用镜像；所有 deb 302 到 GitHub release assets。坏网络单连接约 20–31KB/s。

**修复**：

- 默认 APK：apt 解析 TUR 依赖后，用 Kotlin **8 连接 HTTP Range** 下载大 deb，支持断点续传、SHA256、进度/错误回显，失败回退 apt；默认 APK 不增大。
- opt-in `-PofflineBundle=true`：把 code-server 4.143.1 + nodejs-24 24.18.0 两个大 deb 打包进 APK（+127MB，APK 约 199MB），首次只从快速主镜像拉 ~18MB 依赖，完全不访问 TUR。
- **实测（默认在线）**：清空 app 数据后首次启动，日志出现
  `[net] Downloading 16 package(s) with HTTP Range (up to 8 connections)...`，
  随后 `[net] Downloaded nodejs-24...`；但 TUR/GitHub 对每个连接限速，8 条连接合计仍只有几十 KB/s，139MB 的 code-server 下载仍需很久。**默认在线路径没有真正解决慢网，只是比单连接快。**
- **实测（opt-in bundle）**：用 host 代理预下载两个 deb 到 `D:\amcode-debs`，构建
  `-PofflineBundle=true -PofflineBundleArch=x86_64 -PofflineBundleSourceDir=D:\amcode-debs`，得到 189.75MB APK；清空 app 数据安装后首次启动日志：
  ```
  [offline] Using bundled TUR packages (120 MB): nodejs-24 24.18.0, code-server 4.143.1
  [offline] Bundled packages installed without TUR download.
  [done] All packages installed from bundled debs.
  ```
  只从主镜像拉了 14.7MB 依赖，约 39 秒进入 code-server，**完全不需要 TUR/代理**。截图：[`review-assets/offline-result.png`](review-assets/offline-result.png)、[`review-assets/offline-vscode.png`](review-assets/offline-vscode.png)。
  - 注意：bundle 安装时 dpkg 的 postinst 路径出现双重嵌套（`$PREFIX/data/data/...`），`[dpkg] exit=1; continuing`，但 code-server 文件/启动正常；`pkg` 数据库一致性需后续修。
- 新增 `ensureLibCpp30()`：检测 `libc++_shared.so` 是否含 `_ZNSt6__ndk113__hash_memoryEPKvm`，缺失则 `apt install -y libc++`，实测把 29 升到 30；`spdlog`/`@parcel/watcher` 错误消失。

### 6.3 扩展兼容

- **ripgrep**：TUR 包 13 个 `@vscode/ripgrep-universal/bin/*/rg` 都是指向 `/data/data/com.termux/...` 的绝对 symlink；启动时全部重链到 `$PREFIX/bin/rg`，`rg --version` = 15.2.0。
- **Platform shim**：默认开启 `android-as-linux.js`；由于 VS Code fork 扩展宿主前会删除 `NODE_OPTIONS`，shim 在主进程把 `--require` 追加到 `process.execArgv`，扩展宿主真正看到 `linux`；fileWatcher/ptyHost/terminal 保持 `android`。
- **实测**：扩展宿主 cmdline 带 `--require .../android-as-linux.js`；`spdlog` 不再报错；`rg` 可执行；纯 JS 语言包/editorconfig 可安装激活；glibc native 扩展仍失败。
- Kill switch：`$HOME/.amcode/disable-platform-shim` 或 `AMCODE_ANDROID_PLATFORM_SHIM=0`。

### 6.4 残余

- 默认在线路径完整安装仍需数分钟（TUR/GitHub 限速不是代码能完全消除的）；真正“无 TUR”用 opt-in bundle（189.75MB debug，arm64 类似）。
- bundle 的 dpkg postinst 双重路径问题会导致 `dpkg exit=1`，虽然 code-server 可用，但依赖包配置/数据库不完美；需要把 bundle 安装路径的 `--root`/move 逻辑再对齐。
- 运行时的 Range 下载、bundle 首次安装路径仍需更多网络环境验证；本次已看到默认并行下载启动、nodejs 完成，以及 x86_64 bundle 完整首次安装。
- Linux/glibc native 扩展仍然不可用；需要 proot/glibc 用户态或 VHEditor 式 patched code-server。
- Workspace Trust、Git 未安装、API 26–29、孤儿进程、8 秒 setup 等仍在 backlog。
- **当前默认 app-debug.apk 已重新 clean 构建回 68.74MB，不含 offline-debs**；189.75MB 的 bundle 样例保存在 `D:\amcode-offline-x86_64-debug.apk`（未 commit）。

---

## 7. 第三批修复（2026-10-24 深夜）

### 7.1 默认内部 HOME + `/sdcard` 链接

- 新安装默认 `HOME=/data/user/0/com.devbox/files/home`，不再首启请求 `MANAGE_EXTERNAL_STORAGE`。
- `~/storage/shared -> /storage/emulated/0` 由 app 创建；`/sdcard/AMCode` 文件不搬、不删，仍可通过 Open Folder/Storage action 打开。
- 现有 `home_mode=external` 升级后首启弹一次性迁移对话框（Switch internal / Keep external）；不自动移动文件。
- 实测（5558 fresh offline）：`No persisted HOME; defaulting to internal storage`、`HOME set to internal storage`、`~/storage/shared` 链接存在；Explorer 显示 `home/storage`。
- API 28 fresh：同样 internal，无权限弹窗；grant/deny 旧流程仍可用（C 已测）。

### 7.2 Auth token

- 每次 launch 生成 32 字节随机 token；只把 **SHA-256 hex** 写入 app-private `cache/run/code-server.yaml`（0600）；启动参数：
  `--auth password --config .../code-server.yaml --bind-addr 127.0.0.1:<port>`。
- launcher 自己做 `POST /login` 拿 `code-server-session` cookie，再用 `/mint-key` 校验 200；Activity 在首次导航前把 cookie 注入 `CookieManager`。
- 实测（5558）：
  - 未认证 `GET /` -> `302 Location: ./login`
  - 未认证 `POST /mint-key` -> `401`
  - 正确 cookie `POST /mint-key` -> `200`
  - 正确 cookie `GET /` -> `302` 到 `?folder=...`（不是 login）
  - WebView 日志 `Session cookie installed ... len=64`，页面无登录页。
- 采用 SHA-256 `hashed-password` 而不是明文：4.143.1 对明文每个请求 argon2 ~130–160ms，SHA-256 约 3–6ms；pinned 版本支持该 legacy 路径。

### 7.3 启动快路径 + offline dpkg

- `files/.amcode-pkg-setup` marker：versionCode + setup asset hash + dpkg/termux-tools/keyring 版本 + pkg size/mtime；快检失败才 full repair。
- 实测：
  - 首次 fresh offline：`ensurePkgSetup full repair done in 21971ms ... marker written`（包含 19 包 postinst）。
  - 第二次启动：`ensurePkgSetup fast path: marker valid ... in 122ms`。
- offline dpkg 根因修复：不再 `--root` 双重拼 admin dir；改为 `dpkg --instdir=$PREFIX --unpack` + payload 搬移 + `dpkg --configure -a`。
- 实测 fresh offline（5558/API28）：
  `[dpkg] exit=0 (unpack=0 configure=0 audit=clean; 19 package(s))`；
  `pkg install -y hello` exit 0，`dpkg --audit` 空，`dpkg-query -W code-server nodejs-24` 均为 installed。
- API28 有 `py3compile ... Bad system call` 日志，但 dpkg exit=0/audit clean，code-server 正常启动；记残余。

### 7.4 孤儿进程 + Trust

- PID 文件 `files/run/code-server.pid`（pid/starttime/port/bin，0600）；启动前按 PID+cmdline 清理旧 code-server，Stop/划任务 SIGTERM→SIGKILL 进程树；`stopWithTask=true`。
- 实测 force-stop/restart 后旧 PID 不再存在，新 code-server 正常绑定新动态端口。
- 不传 `--disable-workspace-trust`；外部/共享目录显示 App 级警告 + VS Code Restricted Mode。internal 工作区首次也会显示 Restricted Mode banner，点 Trust 后扩展才启用（按“提示”而非自动信任）。
- 通知新增 Storage action，可切换 internal / 授权共享存储。

### 7.5 双 APK

| 产物 | 大小 | 说明 |
|---|---|---|
| `D:\amcode-release-online.apk` | 64.52MB | 在线版，首次下载 code-server/node |
| `D:\amcode-release-offline-arm64.apk` | 185.70MB | arm64 离线版，包含 code-server/node，首次只拉主镜像小依赖 |
| `D:\amcode-debug-online.apk` | 68.78MB | debug 在线，已安装 5558 |
| `D:\amcode-debug-offline-x86_64.apk` | 189.62MB | x86_64 debug，offline 测试用 |

`app-release-offline-arm64.apk` 已确认含 `assets/offline-debs/aarch64/{code-server,nodejs-24,manifest}`；在线 release 不含 offline-debs。

### 7.6 残余

- internal 工作区首次仍显示 VS Code Restricted Mode banner，必须点 Trust 才启用用户扩展；这是按用户要求保留提示，不自动信任。
- `~/storage/shared` 在 workspace 根下，watcher 可能跟随；后续可加 `files.watcherExclude`。
- 现有旧安装的 code-server/node 不会自动补登记到新 dpkg DB；fresh/offline 安装已正确登记。
- API28 `py3compile Bad system call`（dpkg exit=0/audit clean）；Python 字节码预编译可能不完整。
- 无“切回 external”UI；现有 external 保留，新产品默认 internal；Storage action 提供 internal/授权入口。
- versionCode 仍为 1；正式发版需 bump。release 版尚未 fresh 安装做 UI 回归（debug 已验证）。
- SHA-256 hashed-password 是 code-server 4.143.1 legacy 路径；未来升级需重新验证 auth。
- 默认在线 TUR 仍慢；离线版是正式双版本之一。
