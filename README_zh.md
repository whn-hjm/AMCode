# AMCode — 安卓上的 VS Code

<p align="center">
  <strong>一个 APK，打开就是 VS Code。不需要 Termux，不需要 root。</strong>
</p>

<p align="center"><a href="README.md">English</a> | 简体中文</p>

<p align="center">
  v1.1.0（versionCode 2）· code-server 4.143.1 · Node.js 24.18.0 · Android 8.0+
</p>

---

AMCode 把 [code-server](https://github.com/coder/code-server) 和 Termux 原生用户态打进一个 Android 应用。打开 App 就是一个完整可用的 VS Code —— 写代码、编译、调试，全在手机或平板上。

特别感谢 **[Termux](https://github.com/termux/termux-app)**（ARM64/x86_64 原生 Linux 环境）和 **[code-server](https://github.com/coder/code-server)**（浏览器里的 VS Code），AMCode 只是把它们装进了同一个 APK。

## 两种 APK

| | 在线版（默认） | 离线版（arm64） |
|---|---|---|
| APK 体积 | 约 64 MB | 约 162 MB（仅 arm64） |
| code-server + Node.js | 首次启动从 TUR 下载 | 打包在 APK 内 |
| 首次启动下载 | 约 127 MB（TUR）+ 约 23 MB 依赖 | 仅约 23 MB 依赖，走主镜像（**完全不碰 TUR**） |
| 首次启动自带 git | 否（`pkg install git`） | **是** |
| 适合 | 网络能访问 `tur.kcubeterm.com` | TUR 慢/不通、要可复现安装 |

两种版本其余完全一致。离线版内嵌与在线版相同的、经 SHA-256 校验的 `code-server` 和 `nodejs-24` `.deb`；首次启动会从 Termux 主镜像拉取小依赖**外加 git**，开箱即是一个自给自足的离线开发环境。

## 安装与首次启动

1. 从 [Releases](https://github.com/whn-hjm/AMCode/releases) 下载 APK 安装。
2. 打开 AMCode。**不会请求任何存储权限** —— 初始工作区在应用私有目录（`/data/user/0/com.devbox/files/home`）。
3. 应用解包原生 bootstrap（Termux `bootstrap-2026.08.02-r1+apt.android-7`，每个 ABI 约 32 MB，已打包在 APK 内）并安装 code-server。加载页有实时进度：
   - **在线版：** 约 127 MB 来自 TUR —— 8 连接 HTTP Range 并行下载、支持断点续传 —— 外加约 23 MB 依赖。
   - **离线版：** 仅约 23 MB 依赖，来自 Termux 主镜像。
4. 完成后 VS Code 出现在应用内。

常驻通知保持服务存活，有两个操作：**停止（Stop）** 和 **存储（Storage）**。

## 工作区与存储

- **默认（新装）：** 内部 HOME 位于 `/data/user/0/com.devbox/files/home`。不触碰应用沙箱外的任何东西；`~/storage/shared` 是指向 `/storage/emulated/0` 的符号链接，用于访问共享存储。
- **随时切换：** 通知栏的 **存储（Storage）** 操作（或应用内对话框）可在内部 HOME 和传统共享存储工作区 `/sdcard/AMCode` 之间切换。切换不移动、不删除任何文件。
- **共享存储是 `noexec` 的：** 外部工作区适合编辑文件，但 Git hooks、编译产物、Python venv 无法从那里执行（切换对话框有提醒）。
- **老用户升级：** 如果之前用旧版（外部工作区），首次启动会出现一次性迁移对话框，问是否换到内部 HOME（两种选择都不会动文件）。

## 终端与软件包

按 `` Ctrl+` `` 打开真正的 **bash** —— 跑在 Android 的 bionic libc 上，无 proot、无模拟。

`pkg` 与 Termux 完全一致（同样的仓库、几千个包）。**离线版自带 git**；在线版自行安装：

```bash
pkg install git          # 仅在线版需要（离线版已内置）
pkg install clang make cmake   # C / C++
pkg install python             # Python
pkg install rust               # Rust
pkg install openjdk-17         # Java
pkg install golang             # Go
```

说明：

- 主仓库自动选用三个镜像（清华、中科大、官方）中测速最快的一个；镜像必须响应 HTTP 200 才算可用。
- 所有仓库均为 GPG 签名（主仓 key `5A897D96E57CF20C`、TUR key `6A5C550C260667D1`），密钥打包在 APK 内；初始化完成后不再有 `trusted=yes` 源。
- 安装时 `.deb` 会被实时重打包，Termux 的 `data/data/com.termux` 路径落到 AMCode 自己的包目录下 —— 之后 apt/dpkg 表现正常。

## 扩展

- 扩展市场是 **[Open VSX](https://open-vsx.org)**（code-server 的默认市场），不是微软 Marketplace —— 微软市场只允许官方 VS Code 产品使用。
- **用中文关键词几乎搜不到东西**（Open VSX 的搜索索引只对英文友好）：找语言包请搜 `Simplified` / `zh-hans` / `Japanese` 等英文关键词，或到 open-vsx.org 下载 `.vsix` 后用扩展面板的 *Install from VSIX* 安装。
- 纯 JS 扩展和语言包可以安装运行。**glibc 原生扩展二进制不可用**（运行时是 bionic），带 glibc 预编译 `.node` 的扩展无法加载。
- 平台 shim（`android-as-linux.js`）注入扩展宿主，使扩展看到 Linux 平台（`fileWatcher`、`ptyHost`、终端保持 Android 原生）。开关：创建 `$HOME/.amcode/disable-platform-shim` 文件或设置 `AMCODE_ANDROID_PLATFORM_SHIM=0`。
- 不绕过 VS Code **Workspace Trust**。首次加载会弹出一次性说明；受限模式与信任提示与上游行为一致。只信任你自己的文件夹。
- `ripgrep`（15.2.0）在启动时重新链接，保证全局搜索可用。

## 安全

- code-server 只绑定 `127.0.0.1` 的**动态随机端口**（启动前用临时套接字选取），并启用 `--auth password`。
- 每次启动生成随机 32 字节 token，仅以 SHA-256 十六进制写入 `0600` 配置文件（`cache/run/code-server.yaml`）；token 不会出现在 argv、环境变量或 logcat（stdout 监控做了脱敏）。
- 应用自己完成登录（`POST /login`，经 `/mint-key` 验证），把得到的 **HttpOnly** 会话 cookie 注入 WebView —— 页面接触不到原始密码。
- 其他设备无法访问：只绑 loopback；WebView 未注册任何 JS↔原生桥。
- PID 文件 + `/proc` 校验清理上次残留的 code-server 孤儿进程；划掉任务/停止应用会结束整棵进程树。
- WebView 调试仅在 debug 构建开启。
- 每 10 秒健康检查；崩溃自动重启，最多连续 10 次。

## 系统要求

| | |
|---|---|
| **系统** | Android 8.0+（API 26+） |
| **架构** | ARM64（绝大多数设备）或 x86_64（模拟器） |
| **空间** | 约 1.5 GB 可用（环境 + 软件包 + 工作区） |
| **网络** | 仅首次启动需要（在线版需可达 TUR），之后完全离线 |

TUR（`tur.kcubeterm.com`）没有镜像、部分地区很慢。如果约 127 MB 的 TUR 下载太痛苦，用离线 APK 或代理。

## 构建

```bash
# 前置：JDK 21、Android SDK 34（AGP 8.4.0、Gradle 8.7 —— wrapper 已自带，
# 带 distribution SHA-256 校验）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleRelease                      # 在线版 APK，通用（约 64 MB）
./gradlew assembleRelease -PofflineBundle=true -PofflineBundleArch=aarch64 \
    -PbootstrapArch=aarch64                    # 离线版 APK，仅 arm64（约 162 MB）
# 模拟器用 -PofflineBundleArch=x86_64；-PofflineBundleSourceDir=/path/to/debs
# 可复用已下载的 .deb（文件名需匹配）。
# -PbootstrapArch=aarch64|x86_64 只打一个 ABI 的 bootstrap（APK 小约 33 MB）；
# 省略则打双 ABI 通用包。
# 产物：app/build/outputs/apk/release/app-release.apk
```

- **Bootstrap：** Termux bootstrap 包（aarch64 32,203,582 B / x86_64 32,112,380 B）在首次构建时自动下载（缓存在 `bootstrap-cache/`），以 SHA-256 固定校验；缺失或损坏会让构建失败并给出手动下载指引。`-PbootstrapArch=<abi>` 只打包其中一个。
- **签名：** 在 `app/keystore.properties`（已 gitignore）写 `storeFile`/`storePassword`/`keyAlias`/`keyPassword`，或导出 `AMCODE_STORE_FILE` / `AMCODE_STORE_PASSWORD` / `AMCODE_KEY_ALIAS` / `AMCODE_KEY_PASSWORD` 环境变量。配置写一半会直接构建失败（避免静默产出未签名 APK）；完全不配置则产出未签名 APK。
- 依赖刻意保持精简（无 Material、无 webkit-v1、无 lifecycle-service）；release 开启 R8 收缩。

## 已知限制

- **没有微软 Marketplace** —— 扩展只来自 Open VSX（许可原因）。
- **glibc 原生扩展不可用** —— 运行时是 bionic，glibc 编译的预编译 `.node` 无法加载。
- **共享存储 `noexec`** —— Git hooks、编译产物、venv 需要放在内部 HOME 才能执行。
- **`targetSdk = 28`** —— 应用数据目录里的二进制要能执行（API 29+ 的 W^X 限制），这与 Termux 的取舍相同，也因此无法上架 Google Play。
- **TUR 可能很慢**（无镜像）—— 建议离线 APK 或代理。
- 已容忍的 Termux 已知怪癖：API 28 上 `py3compile` 在 seccomp 下失败；dpkg 视为非致命，下次启动经 `dpkg --configure -a` 自愈。

## 致谢与许可

- [Termux](https://github.com/termux/termux-app) —— 原生 Linux 环境、bootstrap 与软件仓库。
- [code-server](https://github.com/coder/code-server) —— 浏览器里的 VS Code。
- [TUR (Termux User Repository)](https://github.com/termux-user-repository/tur) —— code-server 与 Node.js 软件包。

MIT —— 见 [LICENSE](LICENSE)。

---

AMCode 与 Microsoft、VS Code 项目、Termux 项目及 Open VSX 均无关联。
