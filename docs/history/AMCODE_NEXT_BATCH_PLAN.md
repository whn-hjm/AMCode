# AMCode 下一批计划（2026-10-24）

> 当前基线：`a9971e9`（代码）+ `1abedd7`（文档），上一批已完成。
> 当前设备：只有平板 `emulator-5558`（1600×1200、4:3 横屏）；默认 debug APK 68.74MB 已安装，release 64.50MB 已构建，x86_64 离线 bundle 样例 189.75MB 在 `D:\amcode-offline-x86_64-debug.apk`。

## 0. 用户已确认的产品决策

| 决策 | 选择 |
|---|---|
| 默认工作区 | **内部 HOME** `/data/user/0/com.devbox/files/home` + `~/storage/shared -> /sdcard` 链接；`/sdcard/AMCode` 仍可通过 Open Folder 打开 |
| 认证 | 随机 token（不再 `--auth none`），WebView 自动带 token |
| Workspace Trust | 默认提示用户 Trust，不自动 `--disable-workspace-trust` |
| APK 发布 | 两个版本：`online`（小，首次下载）与 `offline`（约 190MB，打包 code-server/node） |

存储语义说明：当前安装的实际值是 `home_mode=external`，`HOME=/storage/emulated/0/AMCode`；应用 data 只放 bootstrap/`usr`/`.code-server-data`/extensions。`/storage/emulated` 是 FUSE 且 `noexec`，所以外部工作区不适合执行脚本/Git 二进制；内部 HOME 改回正常 Linux 文件系统，`/sdcard` 通过 symlink 访问，不是 mount。

## 0.1 上一批完成情况

| 项 | 状态 |
|---|---|
| `pkg install clang` / NO_PUBKEY / dpkg prefix | 完成并实测 |
| 首次双初始化 / `isReady` 永久 brick | 完成并实测 |
| 输入框 focus / IME 遮挡 | 完成；扩展搜索框一次点击已验证 |
| TUR 并行下载 / opt-in 离线 bundle | 完成；bundle 首次安装实测无 TUR |
| ripgrep / libc++30 / platform shim | 完成；纯 JS 扩展可用，glibc native 仍不可用 |
| commit | `6f44555`、`a9971e9`、`1abedd7` |

## 1. 下一批目标

按用户影响排序：

1. **启动性能**：`ensurePkgSetup` 每次启动约 8 秒，目标是 <1–2 秒且不破坏 pkg 修复。
2. **默认存储策略**：`/sdcard/AMCode` 的 Git/noexec 限制；决定默认内部 HOME 还是外部 HOME，并给 UI/文档。
3. **安全**：code-server 仍 `--auth none`，只 bind 127.0.0.1；同机 app 可访问 loopback。加随机 token。
4. **孤儿进程**：硬杀 app 可能留下 code-server；PID 文件/进程树清理。
5. **API 26–29**：真实旧系统上的存储授权/拒绝/内部 fallback 回归。
6. **离线包正确性**：bundle 安装时 `dpkg postinst` 双重路径导致 `dpkg exit=1`；修到 `dpkg --audit` 干净。
7. **Workspace Trust / Git**：扩展在未信任目录被禁用；`git` 未安装导致 Git 扩展失败。
8. **Release 回归**：release 版在 fresh AVD 上安装，确认 release 无 WebView 调试、focus/auth/pkg 正常。

## 2. 并行工作流（按文件边界）

### A｜启动性能 + 离线包修复

**文件**：`app/src/main/java/com/devbox/TermuxManager.kt`、`app/build.gradle.kts`

- `ensurePkgSetup` 加快：
  - 用版本 marker（APK versionCode + setup script hash）记录已完成的 keyring/SYMLINKS/dpkg wrapper；
  - 每次启动只做 3 个快速 `exists()` 检查；不满足才跑完整 setup；
  - `pkg`/`termux-tools` 升级后通过 marker 失效或轻量检查重新修补。
- 离线 bundle 安装：
  - 修 `dpkg --root` + 已改写 `.deb` 路径导致的 `$PREFIX/data/data/...` 双重嵌套；
  - 目标：bundle 安装后 `dpkg --audit` 空、code-server 可执行、`pkg install` 正常。
- 可选：把 `git` 加进初始依赖/安装提示（与 D 协调，避免重复）。

**验收**：冷启动日志不再跑完整 1092 symlink 扫描；`pkg install -y hello` 仍通过；bundle fresh install `[dpkg] exit=0`。

### B｜存储、安全、Service/进程

**文件**：`MainActivity.kt`、`App.kt`、`CodeServerService.kt`、`CodeServerLauncher.kt`、`AndroidManifest.xml`

- **存储策略（已定）**：
  - 新安装默认 `files/home`（内部，Git/编译/exec 正常），不再默认 `/sdcard/AMCode`；
  - 创建 `~/storage/shared -> /sdcard`（只读/可写取决于权限），并保留 `~/storage/shared/AMCode` 作为共享工作区入口；
  - 现有 `home_mode=external` 的安装保留原设置，提供“切换到内部存储”入口；不自动搬文件；
  - 首次启动不再强制 MANAGE_EXTERNAL_STORAGE；只有用户打开 `/sdcard`/共享目录时才请求；
  - 外部工作区继续显示 FUSE/noexec 提示。
- **auth token**：
  - 启动时生成随机 token/password，`--auth password`（或本地反代）；
  - WebView 通过 Cookie/URL 注入，用户无感；
  - loopback 其他 app 未带 token 时 401。
- **孤儿进程**：
  - 写 PID 文件；启动前清理旧 PID/进程树；`onTaskRemoved`/Stop 统一清理；
  - 保留动态端口。
- **Workspace Trust（产品决策）**：默认提示 Trust，不自动 `--disable-workspace-trust`；或明确接受安全取舍。

- **APK 两个版本**：
  - `online`：默认 APK，约 68MB；首次安装用并行 Range 下载 code-server/node；
  - `offline`：`-PofflineBundle=true`，把 code-server 4.143.1 + nodejs-24 打包（约 190MB）；首次只拉主镜像小依赖，不访问 TUR；
  - release 产物分别命名 `app-release.apk` / `app-release-offline-arm64.apk`；同一 package/signature，用户按网络选择；
  - x86_64 offline 仅用于 emulator-5558 验证。

**验收**：loopback 无 token 401；`~/storage/shared` 可访问 `/sdcard`；内部 HOME 下 `git init`/编译产物可执行；external 旧设置仍能工作。

### C｜API 26–29 + 终端/输入回归

**不改源码**（除非发现小问题）；新建 `AMCode_Tablet_API28`（4:3 平板，端口 5560，可 `-no-window`，不新增可见手机）。

- API 28/29：首次启动 grant/deny READ/WRITE；grant 后 `/sdcard/AMCode`；deny 后内部 HOME；无崩溃。
- 当前平板终端：一次点击 xterm 输入框 → 键盘出现、`echo ok` 可执行；扩展搜索一次点击。
- 键盘 inset：WebView 高度 1200→551，收起恢复；横屏/分屏下复测。
- 如果终端仍不聚焦，针对 `.xterm-helper-textarea` 加 focus/IME 修复（小改 MainActivity）。

### 主 agent｜集成、构建、验证、commit

- 只构建一次（A/B 完成后）：`clean assembleDebug`；release 构建。
- 平板安装默认 APK；fresh data 验证；API28 由 C 提供结果。
- 回归：`pkg install -y hello`、`rg`、extension host、focus、auth、orphan。
- 更新 FIXES/报告，commit（第三批）。

## 3. 验收清单

- [ ] 新装默认内部 HOME；`~/storage/shared` 可访问 `/sdcard`；旧 external 设置仍可切换。
- [ ] auth token 生效；无 token 401。
- [ ] online/offline 两个 release 产物都能安装；offline 首次不访问 TUR。
- [ ] 启动 `ensurePkgSetup` <2s，日志无完整 symlink/DB 扫描。
- [ ] `pkg install -y hello` / `clang --version` 仍 OK，`dpkg --audit` 空。
- [ ] API 26/28/29 grant/deny 无崩溃，internal fallback 可用。
- [ ] 终端/搜索框一次点击弹键盘，IME resize 正常。
- [ ] loopback 无 token 401；WebView 正常。
- [ ] force-stop/restart 无孤儿 code-server。
- [ ] offline bundle fresh install `[offline]`、`dpkg --audit` 空。
- [ ] release APK fresh install，无 WebView 调试、focus/pkg 正常。
- [ ] README/FIXES 与实际行为一致。

## 4. 已确认，不再需要拍板

1. 默认存储：内部 HOME + `/sdcard` 链接。
2. auth：随机 token。
3. Workspace Trust：提示用户 Trust。
4. 离线 APK：作为正式 release 变体发布（arm64）。

## 5. 暂不处理（Wave 2）

- glibc native 扩展：需要 proot/glibc 用户态（CodeFA）或 VHEditor 式 patched code-server；不是小修。
- Microsoft Marketplace：不支持；继续 Open VSX/VSIX。
- 默认在线 TUR 下载速度：并行已做，受 GitHub 限速；主要靠离线 bundle。
- 16KB page/Android 16 新设备回归、Play 上架（targetSdk 28 不可行）。
- UI/UX 大改（extra-keys、mobile layout、virtual mouse）。
