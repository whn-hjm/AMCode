# AMCode 代码与架构评测报告

> 审计对象：`D:\Code\code-server\vsboxed`（包名 `com.devbox`，对外名 AMCode）  
> 审计基线：`git HEAD = 2d9b486`，与 GitHub `origin/main` 完全一致；GitHub Release `v1.0` 的 tag 是 `fc57544`（比 HEAD 落后 8 个提交）。  
> 审计日期：2026-10-24  
> **修复状态（同日）**：P0-2/P0-4/P0-5 与大部分 P1 已在本轮修复并在平板 AVD 上验证；具体改动、测试证据和残余风险见 [AMCODE_FIXES_2026-10-24.md](AMCODE_FIXES_2026-10-24.md)。  
> 方式：全量静态代码审查 + bootstrap 资产解包比对 + Termux `.deb` 内容验证 + Gradle 构建/lint/APK 分析 + 上游项目对照；4 个子代理分别从 Android 层、Termux/apt 层、上游对比、构建层独立审计。  
> 限制：没有在真机上安装运行；运行时结论标注了“已确认（代码/构建）”或“需真机验证”。本报告不修改任何源码。

---

## 0. 结论摘要

**这是一个能跑通核心链路的优秀 MVP，但还不是可发布产品。**

它最难的部分其实做对了：把 Termux 原生运行时装进 APK、用等长包名 `com.devbox` 替换 `com.termux`、`targetSdk=28` 绕过 W^X、用前台服务保活、WebView 加载 code-server。这些设计是成立的，作者在 `DEVELOPMENT.md` 里记录的 21 个坑也很真实。

但代码里存在 **5 类系统性问题**，不是“几个小 bug”：

1. **默认安全配置是危险的**：code-server 监听 `0.0.0.0:8080` 且 `--auth none`，同一 Wi‑Fi 下任何人都能打开你的 IDE 并执行终端命令；同机其他 app 也能访问 loopback。
2. **启动/服务生命周期没有 single-flight**：`MainActivity` 在多数路径上会启动两次 Service，Service 又会无条件再跑一遍初始化；首启可能并发解压、并发 apt、并发起两个 code-server。
3. **Termux 安装器只复刻了一半**：`SYMLINKS.txt` 完全没读，`termux-exec` 没启用；标准 `#!/usr/bin/env` shebang、gawk、部分开发库链接会缺失或退化到 Android toybox。
4. **包管理架构不成立**：初始安装用 `dpkg --root` + 手工搬文件，但后续 `pkg/apt` 没有 `--root`；Termux `.deb` 里写死的是 `./data/data/com.termux/files/usr/...`，`pkg install` 实际装不进 `com.devbox` 的 prefix。
5. **恢复路径几乎没有**：`isReady()` 只看 `bash/apt/lib`，bootstrap 一解压完就为 true；一旦首次 236MB 安装失败或被中断，下次启动会直接跳过安装，永远停在 “code-server not installed”，只能清数据/重装。

另外还有三个“产品级”问题：

- **Android 8–10 的存储权限没有申请**，却直接把 `/sdcard/AMCode` 设为 HOME；这些系统上首启会失败。
- **默认把 HOME/工作区放在 `/sdcard`**，而 Android 共享存储不支持 Unix 权限/符号链接，且通常 noexec；`git` 官方文档明确说在 `/sdcard` 不工作，编译产物、venv、npm `.bin` 也会受影响。
- **发布物与源码不一致**：GitHub Release 的 APK 来自 `fc57544`，没有包含 HEAD 最后的 termux-keyring/镜像排除/实验回滚；README 又让用户从 Releases 下载。

我给一个粗略判断：

| 维度 | 评价 |
|---|---|
| 核心功能完成度 | 70%：VS Code 编辑、文件浏览、基础终端可用 |
| 首启/恢复稳定性 | 40%：并发初始化、半成品不可恢复、权限流程断裂 |
| 安全性 | 25%：无认证监听所有网卡、WebView 调试常开、JS bridge 过宽 |
| 性能/体积 | 55%：能跑，但 67.6 MB APK 中 95% 是两个 bootstrap；首启 236MB 无进度/无恢复 |
| 可维护性 | 55%：结构清晰、注释和文档不错；但无测试/CI、死代码、硬编码多 |
| 发布就绪度 | 20%：release 落后 HEAD、clean clone 无法构建 release、x86_64 资产缺失 |

**最优先修复顺序（如果只做 5 件事）：**

1. `--bind-addr 127.0.0.1` + 随机认证 token；release 关闭 WebView 调试。
2. 修 `MainActivity` 权限/启动状态机 + Service `initJob` single-flight（消灭重复初始化）。
3. Android 26–29 申请 `READ/WRITE_EXTERNAL_STORAGE`；`setHomeDir` 检查 `mkdirs`，失败回退内部存储。
4. 安装完成标记（`.amcode-ready`）+ 半成品恢复 + 校验/清理 bootstrap；`isReady` 不要把“解压完”当“装完”。
5. 解决 `pkg install` 的 `.deb` prefix 问题（重写 data.tar 路径，或明确下线该承诺）。

---

## 1. 项目理解

### 1.1 实际架构

```
MainActivity (WebView, http://localhost:8080)
   │  bindService
   ▼
CodeServerService (foreground service, health check 10s)
   │
   ├─ TermuxManager.initialize()
   │    1. create dirs
   │    2. copy assets/bootstrap-*.zip → filesDir/bootstrap-aarch64.zip
   │    3. unzip → files/usr
   │    4. patch ELF/text: /data/data/com.termux/files/usr → /data/data/com.devbox/files/usr
   │    5. create a few symlinks (sh, coreutils subset, lib*.so)
   │    6. mirror test + apt sources
   │    7. apt update → apt install -d code-server → dpkg --root + move files
   │
   └─ CodeServerLauncher.start()
        code-server --bind-addr 0.0.0.0:8080 --auth none ...
```

关键设计：

- **等长包名**：`com.termux` 和 `com.devbox` 都是 10 字符，可以对 ELF/text 做等长字节替换。
- **native 而不是 proot**：Termux 二进制直接跑在 bionic 上，避免 ptrace 开销。
- **targetSdk=28**：进入 `untrusted_app_27`，允许执行 app data 里的二进制，绕过 Android 10+ 的 W^X。
- **单 APK**：两个 ABI 的 bootstrap 都塞进 assets，运行时按 `Build.SUPPORTED_ABIS` 选。
- **code-server 来自 TUR**：`code-server 4.143.1`，依赖 `nodejs-24`、`libandroid-spawn`、`libsecret`、`krb5`、`ripgrep`；`libandroid-spawn` 正是 Termux 上 node-pty/终端能工作的关键依赖之一。

### 1.2 做得好的地方

- 6 个 Kotlin 文件、约 1800 行，职责分得清；注释解释了 W^X、dpkg、符号链接等背景。
- `DEVELOPMENT.md` 记录了 21 个真实踩坑，说明作者理解这些问题的机理。
- 构建能成功：`assembleDebug`、`lintDebug`、`assembleRelease` 全部通过；release 签名、zipalign、bootstrap STORED 都正常。
- code-server 的启动参数（`--disable-telemetry`、`--disable-update-check`、独立 user-data/extensions 目录）是对的。

### 1.3 根本矛盾

AMCode 的核心技巧是“把 `com.termux` 等长替换成 `com.devbox`”。这对 **ELF 和文本脚本**有效，但对 **`.deb` 压缩包内部路径**无效：

- Termux 包的数据路径是 `./data/data/com.termux/files/usr/...`（我下载了官方 `hello` 包验证）。
- 初始安装用 `dpkg --root=$PREFIX`，所以路径落在 `$PREFIX/data/data/com.termux/files/usr/...`，然后手工 `cp` 到 `$PREFIX/`。
- 但用户后续在终端执行 `pkg install`，走的是 stock `pkg` → apt → dpkg；apt 的 `Dir` 只影响 apt 自己的目录，**不会**给 dpkg 传 `--root`（apt 只认 `DPkg::Chroot-Directory`，项目没设）。dpkg 按 `.deb` 里的绝对路径写 `/data/data/com.termux/...`，app UID 没有权限。
- 结果：**初始 code-server 能装进去，后续 `pkg install` 装不进去**。

这不是一个小补丁能解决的，需要选一条路线：重写 `.deb` 路径、给 dpkg 做 wrapper、用 proot、或自建 `com.devbox` prefix 的仓库。详见 P0-5。

---

## 2. P0：必须先处理的问题

### P0-1 无认证 code-server 监听 `0.0.0.0`，等于局域网 RCE

**位置**

- [`CodeServerLauncher.kt:83-91`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L83-L91)：`--bind-addr 0.0.0.0:8080`、`--auth none`
- [`ARCHITECTURE.md:185`](ARCHITECTURE.md#L185)、[`ARCHITECTURE.md:188-189`](ARCHITECTURE.md#L188-L189)：声称“only localhost reachable”

**触发/影响**

- 手机和电脑在同一 Wi‑Fi，电脑访问 `http://手机IP:8080` 就能打开完整 VS Code；集成终端以 `com.devbox` 的 UID 运行，可以读写 `/sdcard/AMCode`、app 私有目录、Termux 工具链。
- 不需要密码，也没有登录页；等于把一台“以 app 权限运行的 Linux”暴露给局域网。
- 即使改成 `127.0.0.1`，Android 也没有 per-app loopback 隔离：同机任何有 `INTERNET` 权限的 app 都能访问 `127.0.0.1:8080`。所以“只 bind localhost”只能挡住局域网，挡不住本机恶意 app。
- `ARCHITECTURE.md` 的安全说明是错的：`0.0.0.0` 就是所有 IPv4 接口；Android 默认没有入站防火墙。

**证据**

- 代码里明确是 `0.0.0.0` 和 `--auth none`。
- 同一份 `ARCHITECTURE.md` 第 122 行又写了 `--bind-addr 0.0.0.0:8080 --auth none`，与第 188 行的“only localhost”自相矛盾。

**修复**

最小：

```kotlin
"--bind-addr", "127.0.0.1:$port",
"--auth", "password", // 启动时生成随机密码，写入私有 prefs
```

WebView 侧用 `CookieManager` 写入 code-server 会话，或做一个只在 loopback 上的反向代理注入 token。若确实要支持局域网，必须做成显式开关，并加 TLS + 强 token，而不是默认开放。

**验证**

- 同网 PC：`curl http://手机IP:8080/healthz` 当前应返回 200；修复后应连接失败或 401。
- `adb shell cat /proc/net/tcp` / `ss -ltnp` 应只看到 `127.0.0.1:port`。
- 同机另一个 app 访问 loopback 应被认证挡住。

**置信度**：CONFIRMED（代码）；LAN 可达性需真机复现一次。

---

### P0-2 多数启动路径会重复启动 Service，Service 又没有 single-flight，首启可能并发解压/并发 apt

**位置**

- [`MainActivity.kt:150-172`](app/src/main/java/com/devbox/MainActivity.kt#L150-L172)：`checkStoragePermission()` 之后继续走通知/启动逻辑。
- [`MainActivity.kt:321-371`](app/src/main/java/com/devbox/MainActivity.kt#L321-L371)：存储权限分支里已经调用 `proceedAfterPermission()`。
- [`MainActivity.kt:373-384`](app/src/main/java/com/devbox/MainActivity.kt#L373-L384)：`proceedAfterPermission()` 内部会 `startCodeServerService()`。
- [`MainActivity.kt:388-405`](app/src/main/java/com/devbox/MainActivity.kt#L388-L405)：每次调用都 `startForegroundService` + `bindService`。
- [`CodeServerService.kt:67-82`](app/src/main/java/com/devbox/CodeServerService.kt#L67-L82)：`onStartCommand` 无条件 `initializeAndStart()`，没有幂等保护。
- [`TermuxManager.kt:113-205`](app/src/main/java/com/devbox/TermuxManager.kt#L113-L205)：`initialize()` 只在 `isReady()` 为 true 时提前返回；首次解压前后都是 false，两个协程会同时进入。
- [`MainActivity.kt:319`](app/src/main/java/com/devbox/MainActivity.kt#L319)：`storageResolved` 只写不读，是死状态，正好说明这个流程没有收敛。

**触发/影响**

- 权限已授予时：`checkStoragePermission()` 会同步走到 `proceedAfterPermission()` → `startCodeServerService()`；随后 `onCreate` 第 153–172 行再启动一次。**每次 Activity 创建至少两次 Service start。**
- 首启 API 30–32：存储对话框还显示着，`onCreate` 已经启动 Service；用户授权后回调再启动一次。
- 首启 API 33+：`onCreate` 和 `proceedAfterPermission()` 各自请求一次通知权限，两个回调都可能启动 Service；存储回调还会再启动一次。
- 两个 `initializeAndStart()` 并发时：
  - 两个协程同时向同一目录解压 32MB bootstrap、写同一个临时 zip；
  - 两个 `apt update` / `dpkg` 同时操作同一 dpkg 数据库；
  - 第二个可能在第一个还在安装时先执行 `prepareAndStart()`，报 “code-server not installed” 然后 `stopSelf()`，而第一个仍在继续；
  - 即使环境已 ready，也会起两个健康检查 Job；`healthCheckJob` 字面量被覆盖，旧 Job 无法取消。
- 再叠加 `configChanges` 不完整（缺 `uiMode|fontScale|density|locale` 等），切深色模式/字体大小/语言会重建 Activity，又走一遍。

**修复**

```kotlin
// MainActivity
private var started = false
private fun startOnce() {
    if (started) return
    started = true
    startCodeServerService()
}

// 权限流程只在一个地方结束
private fun checkStoragePermission(onResolved: () -> Unit) { ... onResolved() ... }

// onCreate 里不要既让 checkStoragePermission 启动，又自己启动
```

```kotlin
// CodeServerService
private var initJob: Job? = null
private fun initializeAndStart() {
    if (initJob?.isActive == true) return
    initJob = scope.launch { ... }
}
```

Activity 侧最好做成一个显式状态机：`CHECK_STORAGE → REQUEST_STORAGE → CHECK_NOTIFICATION → REQUEST_NOTIFICATION → START_SERVICE`；只有最后一步启动 Service。`configChanges` 至少补 `uiMode|fontScale|density|locale`，或把初始化状态放进 Service/ViewModel。

**验证**

- 在 `initializeAndStart()` 入口加计数日志；首启、切深色、改字体、转屏，logcat 应只有一次。
- 首启后检查 `files/usr` 没有重复/损坏，`dpkg --audit` 正常，`ps` 只有一个 code-server。
- 用 StrictMode/协程调试器确认没有两个 init 并发。

**置信度**：CONFIRMED（代码路径）；文件损坏是机制推导，需真机复现。

---

### P0-3 Android 8–10 完全不申请存储运行时权限，却把 `/sdcard/AMCode` 设为 HOME

**位置**

- [`MainActivity.kt:321-335`](app/src/main/java/com/devbox/MainActivity.kt#L321-L335)：`SDK_INT < R` 分支直接 `setHomeDir(File(Environment.getExternalStorageDirectory(), "AMCode"))`。
- [`AndroidManifest.xml:20-23`](app/src/main/AndroidManifest.xml#L20-L23)：只声明 `READ/WRITE_EXTERNAL_STORAGE`，但全工程没有 `requestPermissions`。
- [`TermuxManager.kt:217-221`](app/src/main/java/com/devbox/TermuxManager.kt#L217-L221)：`dir.mkdirs()` 返回值被忽略。
- [`CodeServerLauncher.kt:91-93`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L91-L93)：`ProcessBuilder.directory(TermuxManager.homeDir)` 在目录不存在时抛异常。

**触发/影响**

- 在 Android 8.0/9/10（API 26–29）新安装，第一次启动不会弹存储权限框，直接使用 `/sdcard/AMCode`。
- Android 6+ 的危险权限必须运行时申请；`maxSdkVersion` 只是声明上限，不会自动授权。
- 没有授权时 `mkdirs()` 失败；随后 `createDirectories()` 写 `/sdcard/AMCode/.termux` 失败，`code-server` 的 `directory(homeDir)` 不存在，Service 进入 ERROR 并 `stopSelf`。
- 用户看到 “Failed: ...” 且没有重试入口；README 说的“首次启动会询问存储权限”在 API 26–29 上不成立。
- 这也解释了为什么作者在 Android 16 真机上测试没问题，但 README 声称支持 Android 8+。

**修复**

- API 23–29 用 `ActivityResultContracts.RequestMultiplePermissions` 请求 `WRITE_EXTERNAL_STORAGE`（必要时 `READ_EXTERNAL_STORAGE`）。
- 只有授权成功、`Environment.getExternalStorageState()` 可写、`dir.canWrite()` 为 true 时才 `setHomeDir()`；否则回退内部 `files/home`。
- `setHomeDir()` 检查 `mkdirs()` 结果并返回 Boolean；失败时明确提示。
- API 30+ 保留 `MANAGE_EXTERNAL_STORAGE` 流程，同时给 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 加 `ActivityNotFoundException` fallback。

**验证**

- 全新安装到 API 28/29 模拟器：`adb shell dumpsys package com.devbox | grep -i WRITE` 应能看到权限状态；logcat 不应出现 `Cannot run program ... No such file or directory`。
- 手动在系统设置授予存储权限后重启 app，应能成功进入 VS Code。

**置信度**：CONFIRMED（代码路径明确）；具体报错文本需设备确认。

---

### P0-4 `isReady()` 把“bootstrap 解压完”当成“安装完成”，一次网络失败后永久无法恢复

**位置**

- [`TermuxManager.kt:207-211`](app/src/main/java/com/devbox/TermuxManager.kt#L207-L211)：`isReady()` 只检查 `bin/bash`、`bin/apt`、`lib/`。
- [`TermuxManager.kt:116-120`](app/src/main/java/com/devbox/TermuxManager.kt#L116-L120)：`isReady()` 为 true 直接返回成功，跳过整个安装流程。
- [`TermuxManager.kt:175-194`](app/src/main/java/com/devbox/TermuxManager.kt#L175-L194)：`installPackages()` 只在 `isReady()==false` 的首次分支中执行。
- [`CodeServerService.kt:125-131`](app/src/main/java/com/devbox/CodeServerService.kt#L125-L131)：注释声称 “Packages are verified by initialize()”，与事实不符。
- [`CodeServerLauncher.kt:54-55`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L54-L55)：code-server 不存在时只返回 failure。

**触发/影响**

- 我核对了打包的 `bootstrap-aarch64.zip`：其中包含 `bin/bash`、`bin/apt`、`lib/`。所以**解压刚完成、还没安装 node/code-server 时，`isReady()` 就已经是 true**。
- 更糟的是 `createDirectories()` 在解压前就建好了 `lib/`，而 zip 的 `bin/bash`/`bin/apt` 在条目前部；即使解压到一半被杀，`isReady()` 也可能已经为 true，下次直接跳过解压/patch/symlink。
- 若此时网络中断、磁盘满、app 被杀、apt 失败、或用户手动停止，下一次启动：
  1. `initialize()` 立即返回 “Environment ready”；
  2. `prepareAndStart()` 报 “code-server not installed”；
  3. Service 进入 ERROR 并 `stopSelf`；
  4. 由于 `isReady()` 永远为 true，app 永远不会再尝试 `installPackages()`。
- 用户只能“清除应用数据/重装”才能恢复。这是首次 236MB 下载场景下非常容易触发的死路。
- 还有更隐蔽的半成品：如果 app 在“解压完成、patch/symlink 之前”被杀，下次启动会跳过 patch/symlink；环境看起来有 bash，但 RUNPATH/软链不完整。
- `bootstrapZip.exists()` 只判断存在，不校验大小/哈希；截断的 zip 也会被当作缓存。

**修复**

- 安装全部成功（`isNodeInstalled() && isCodeServerInstalled()` 且 code-server 可执行）后写完成标记：`File(usrDir, ".amcode-ready")`，内容包含 version/arch。
- `isReady()` 改为：标记文件存在 + `bin/bash` + `lib/`；标记不存在时，若 bootstrap 完整则只重跑 `installPackages()`，不要重复解压。
- 解压/安装失败时删除 `bootstrap-*.zip` 和半成品 `usr/`，下次重新开始；bootstrap 固定 SHA-256 并在解压前校验。
- 解压时校验 `targetFile.canonicalPath.startsWith(usrDir.canonicalPath + File.separator)`，防止 zip slip。
- 删除误导性的 “Packages are verified” 注释。

**验证**

- 首次安装到一半断网/杀进程；重新打开 app，当前版本会直接失败，修复后应重新进入安装重试并最终成功。
- 用截断的 zip/错误哈希的资产做单元测试，应被拒绝并清理。
- 检查 `files/usr/.amcode-ready` 只在完整安装后出现。

**置信度**：CONFIRMED（代码 + 已核对 bootstrap 内容）。

---

### P0-5 `pkg install` 实际不可用：`.deb` 里的 `com.termux` 路径与 `com.devbox` prefix 不匹配

**位置**

- [`TermuxManager.kt:481-493`](app/src/main/java/com/devbox/TermuxManager.kt#L481-L493)：apt 配置只写 `Dir`，没有 `DPkg::Chroot-Directory` / `DPkg::Options --root`。
- [`TermuxManager.kt:570-577`](app/src/main/java/com/devbox/TermuxManager.kt#L570-L577)：初始安装用 `dpkg --root=$usrDir --force-all -i *.deb`，然后把 `$usrDir/data/data/com.termux/files/usr/*` 搬到 `$usrDir/`。
- [`TermuxManager.kt:544-603`](app/src/main/java/com/devbox/TermuxManager.kt#L544-L603)：后续安装逻辑只在首次初始化里跑；`pkg`/`apt` 本身没有 wrapper。
- [`ARCHITECTURE.md:72-79`](ARCHITECTURE.md#L72-L79)：作者自己写了 “.deb 包硬编码 com.termux 路径”。

**验证证据**

我下载了 Termux 官方 `hello_2.12.3-1_aarch64.deb` 并解包，`data.tar.xz` 的成员是：

```
./data/data/com.termux/files/usr/bin/hello
./data/data/com.termux/files/usr/share/doc/hello/...
```

`dpkg` 的默认 `instdir` 是 `/`；Termux 在真机上能装，是因为 app UID 拥有 `/data/data/com.termux`。AMCode 的 app UID 只拥有 `/data/data/com.devbox`。初始安装靠 `--root=$usrDir` 把绝对路径落到 `$usrDir/data/data/com.termux/...`（可写），再手工搬走。后续 `pkg install` 走 stock apt：

- apt 的 `Dir "$usrDir"` 只改变 apt 自己的状态/缓存目录；
- apt 调用 dpkg 时，只有 `DPkg::Chroot-Directory` 会改变 dpkg 的 root（apt 源码 `dpkgpm.cc`），项目没有设置；
- 所以 dpkg 仍按 `.deb` 里的 `/data/data/com.termux/...` 写，app UID 没有权限；
- 而且 dpkg 的 status/list 里记录的是 `com.termux` 路径，与后来搬走的实际文件也不一致。

补充（Termux 子代理进一步核对）：

- bootstrap 自带的 `var/lib/dpkg/status` 已经记录了 82 个包，`var/lib/dpkg/info/*.list` 全是 `/data/data/com.termux/files/usr/...`；首次安装后 `rm -rf $PREFIX/data`，这些 `.list` 直接指向已删除路径。所以即使 dpkg 能装新包，`dpkg -L`/`apt remove`/`pkg upgrade` 的数据库也已经失真。
- TUR `code-server_4.143.1_aarch64.deb` 的 `lib/code-server/lib/node` 是指向 `/data/data/com.termux/files/usr/opt/nodejs-24/bin/node` 的**绝对符号链接**；项目专门修了这个链接，但 TUR 给 `@vscode/ripgrep-universal/bin/*/rg` 建的同类绝对链接没有修，搜索可能指向不存在的旧路径。
- 还有第二重失败：`bin/pkg` 的 `select_mirror()` 会把 `sources.list` 改写成不带 `trusted=yes` 的 `deb $MAIN stable main`；而 `etc/apt/trusted.gpg.d/` 的 8 个 key 本身是 `SYMLINKS.txt` 里未创建的链接。`pkg` 开头 `set -eu`，`apt update` 的 `NO_PUBKEY` 会直接终止。也就是说，即使解决了 dpkg 的 `--root` 问题，`pkg update` 仍可能失败。

**影响**

- README/Release notes 的“3000+ 软件包，pkg/apt 可用”不成立。
- 用户执行 `pkg install clang make cmake`、`pkg install python`、`pkg install git` 会失败（或在 `dpkg` 阶段报权限/路径错误；或先被 `NO_PUBKEY` 挡住）。
- `pkg upgrade`、`pkg uninstall`、`pkg files` 也会因为路径/status 不一致而不可靠。
- 初始 `code-server` 本身能工作，是因为安装逻辑手工绕过了这个问题；一旦用户想扩展工具链就撞墙。
- 这是 native-bionic 方案的系统性边界，不是“换个参数”就好。

**修复选项**

1. **重写 `.deb` 路径（推荐，长期正确）**：在 apt 下载后、dpkg 安装前，解开 `data.tar.*`，把 `data/data/com.termux/` 改成 `data/data/com.devbox/`，重新打包。可以用 `DPkg::Pre-Install-Pkgs` hook + `dpkg-deb -R/-b` 实现；路径等长，压缩包需要重压，但一次性成本可接受。
2. **给 dpkg 做 wrapper + `--root` + move**：apt 配置 `DPkg::Options { "--root=$usrDir"; }`，再挂 `DPkg::Post-Invoke` 把 `$usrDir/data/data/com.termux/files/usr/*` 搬到 `$usrDir/` 并 patch 文本。能复用初始安装的思路，但要处理 status/list 路径、脚本执行顺序、并发锁。
3. **符号链接取巧**：创建 `$usrDir/data/data/com.termux/files/usr -> $usrDir`，让 `--root` 下的绝对路径解析到真实 prefix。需要真机验证 dpkg 是否接受目录项/是否替换 symlink；不建议作为唯一方案。
4. **proot/Ubuntu（CodeFA 路线）**：包管理和扩展兼容性最好，但放弃 native 性能优势，APK/内存更大。
5. **自建 `com.devbox` prefix 的 Termux 仓库**：最彻底，但构建/维护成本最高。

如果短期不打算做 1–3，**至少把 README/Release notes 里的 `pkg install` 承诺去掉**，明确“预装 code-server + 基础工具，扩展工具链需自行编译/安装 .deb”。

**验证**

- 在真机终端执行 `pkg install hello`，观察 dpkg 是否尝试写 `/data/data/com.termux/...`；修复后 `which hello` / `hello` 应可用。
- **已在 API 34 x86_64 模拟器（独立测试副本）实测**：`apt-get -y install hello` 能下载 32KB，但 dpkg 报：
  ```
  dpkg: error processing archive .../hello_...deb (--unpack):
   error creating directory './data/data/com.termux': Permission denied
  E: Sub-process .../dpkg returned an error code (1)
  ```
  这直接确认了 P0-5：不是网络/镜像问题，是 `.deb` 路径与 prefix 不匹配。
- `dpkg -L hello` 应指向 `$PREFIX/bin/hello`，而不是 `com.termux` 路径。
- `pkg upgrade` 后 `dpkg --audit` 应为空。

**置信度**：高置信度（.deb 内容 + apt 源码 + 项目自身文档）；具体报错文本需真机确认。

---

## 3. P1：高优先级问题

### P1-1 默认 HOME/工作区在 `/sdcard`，Git、编译产物、venv、npm 都会受限

**位置**

- [`MainActivity.kt:325`](app/src/main/java/com/devbox/MainActivity.kt#L325)、[`MainActivity.kt:331`](app/src/main/java/com/devbox/MainActivity.kt#L331)、[`MainActivity.kt:366`](app/src/main/java/com/devbox/MainActivity.kt#L366)：`setHomeDir(/sdcard/AMCode)`。
- [`TermuxManager.kt:96-107`](app/src/main/java/com/devbox/TermuxManager.kt#L96-L107)：`HOME` 直接取 `homeDir`。
- [`CodeServerLauncher.kt:91-93`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L91-L93)：workspace 也取 `homeDir`。
- README 明确推荐授予存储权限、使用 `/sdcard/AMCode`。

**影响**

- Android 共享存储（FUSE）不提供完整的 Unix 权限/符号链接语义。Termux wiki 明确说不能把需要权限/符号链接/socket 的文件放在 sdcard。
- code-server 官方 Termux 文档专门有一节 **“Git won't work in `/sdcard`”**：在 `/sdcard` 里 clone/commit/stage 会失败；推荐用 Termux 内部目录或软链。
- 编译产物通常不可执行（共享存储一般 noexec），`./a.out` 会 “Permission denied”。
- Python `venv`、npm `node_modules/.bin`、Git hooks 依赖符号链接/可执行位，可能失败。
- 文件监视（inotify）在 FUSE 上不可靠，终端里创建的文件，VS Code 资源管理器可能不自动刷新。
- `HOME` 在 sdcard 还意味着 `.gitconfig`、`.npm`、`.cache`、`.bashrc` 都在共享存储，增加兼容性问题。

**修复建议**

- 默认 HOME 改为内部 `files/home`（Termux 正常做法），把 `/sdcard/AMCode` 作为“用户文件区”通过 `~/shared` 软链或 VS Code 的“打开文件夹”访问。
- 或者保留外部工作区，但把 `HOME` 固定到内部；构建/Git 建议在内部目录进行。
- UI 明确提示：“共享存储不能执行二进制/不支持 Git 完整语义；需要编译请用内部工作区”。
- 持久化用户选择（见 P1-5）。

**置信度**：高（code-server 官方文档 + Termux wiki）；具体 noexec 行为随 ROM 不同，需设备确认。

---

### P1-2 `termux-exec` 没启用、`SYMLINKS.txt` 没读：标准 shebang 和部分工具链不完整

**位置**

- [`TermuxManager.kt:96-107`](app/src/main/java/com/devbox/TermuxManager.kt#L96-L107)：环境变量里没有 `LD_PRELOAD`。
- [`TermuxManager.kt:287-364`](app/src/main/java/com/devbox/TermuxManager.kt#L287-L364)：解压后只根据 `.so.X.Y` 正则创建库软链，没有读 `SYMLINKS.txt`。
- [`TermuxManager.kt:503-536`](app/src/main/java/com/devbox/TermuxManager.kt#L503-L536)：手工创建 `sh`、`https`、39 个 coreutils applet 和少量库链接。
- bootstrap 的 `SYMLINKS.txt` 有 1161 条；`bin/login` 会设置 `LD_PRELOAD=.../libtermux-exec-ld-preload.so`，但 code-server 直接启动 bash，不经过 `login`。

**影响**

- **标准 shebang 失效**：`#!/usr/bin/env python`、`#!/usr/bin/env node`、`#!/bin/sh`（在部分 Android 版本）等依赖 `termux-exec` 重写；没有 `LD_PRELOAD` 时，`/usr/bin/env` 根本不存在，脚本直接 ENOENT。
- 影响 pip/npm 安装的 CLI、`./configure`、部分构建脚本、`make` 的子 shell（取决于 `/bin/sh` 是否存在）。
- **`libtermux-exec.so` 软链缺失**：`SYMLINKS.txt` 里有 `libtermux-exec.so`，手工逻辑不会创建；虽然 `libtermux-exec-ld-preload.so` 实际存在，但 `login`/文档路径不一致。
- **约 97 个 bin 软链缺失**（136 个 bin 链接减去手工创建的 39 个），包括 `ls/pwd/id/whoami/uname/date/awk/which/[ /mktemp/sha256sum` 等。因为 PATH 含 `/system/bin`，常见命令**可能**回退到 Android toybox，**不会全部 command not found**（是否如此取决于 ROM/Android 版本；例如 `awk` 在部分 ROM 上就没有 toybox 替代）；但：
  - `gawk` 存在却没有 `awk` 链接，脚本会用到 toybox awk 或直接失败，GNU 特性不兼容；
  - coreutils 的选项/行为与 toybox 不同；
  - `git`/`make`/`configure` 等脚本对工具行为敏感。
- **约 29–37 个顶层库软链缺失**：其中一部分被 `.so.X.Y` 正则补上，但 `libcrypto.so`、`libssl.so`、`libevent*.so`、`libncurses.so`/`libcurses.so`/`libtermcap.so`、`libtinfo.so`、`libtermux-exec.so` 等不会；影响 C/C++ 链接（`-lcrypto`、`-lssl`）、ncurses 兼容和 termux-exec。
- **路径 patch 覆盖不全**：解压树中有 611 个文件、约 6790 处含 `/data/data/com.termux/files/usr`；`patchBinaries` 只覆盖 338 个文件/897 处，重点是 `bin`/`lib` 顶层，`opt/`、`var/lib/dpkg/info/*.list`、`lib/apt/methods` 子目录、`libexec` 子目录和包内绝对 symlink 没覆盖。`nodejs-24` 在 `opt/` 里，app 只修了 `lib/code-server/lib/node`，`opt` 内二进制/脚本的旧前缀可能残留。
- **`bin/npm`/`npx` 受影响**：TUR nodejs-24 的 npm/npx 是 JS CLI，shebang 为 `#!/usr/bin/env node`；没有 termux-exec，用户即使把 `opt/nodejs-24/bin` 加进 PATH，npm 也可能起不来。
- **模拟器实测**：
  - `#!/usr/bin/env sh` 脚本执行直接报 `No such file or directory`（`/usr/bin/env` 不存在）；
  - `command -v awk` 返回 `/system/bin/awk`（Android toybox），不是 Termux 的 gawk；
  - code-server 启动后 extension host 报 `ripgrep-universal/bin/android-x64/rg ENOENT`，以及 `spdlog.node` `dlopen failed: cannot locate symbol _ZNSt6__ndk113__hash_memoryEPKvm`。前者是 TUR 包里的绝对 symlink 未被 patch（指向旧 `com.termux` prefix），后者是 native 模块 ABI 不匹配；两者都会让搜索/扩展宿主功能不完整。
  - 这些不是“代码读起来可能有问题”，而是已经在模拟器里出现的行为。
- 说明：Android toybox 兜底让“ls 完全不能用”这种极端情况不一定出现；真正的问题是 **Termux 工具链不完整、行为不一致、开发库缺失**。

**修复**

1. 像 `TermuxInstaller.java` 一样解析 `SYMLINKS.txt`：每行 `target←link`，在 `$PREFIX/link` 创建指向 `target` 的符号链接（target 是相对于 link 所在目录的路径）。这一步能一次性补齐 1161 条链接。
2. 设置 `LD_PRELOAD=$PREFIX/lib/libtermux-exec-ld-preload.so`（或先 `libtermux-exec.so`），并验证 `coreutils --coreutils-prog=true`；对 code-server 进程和 shell 都生效。
3. 保留手工 coreutils 兜底可以，但不要再维护一份不完整的 applet 列表。
4. 增加启动自检：`sh -c '#!/usr/bin/env sh\ntrue'`、`awk --version`、`gcc -lcrypto` 之类 smoke test。

**验证**

- `printf '#!/usr/bin/env sh\necho ok\n' > /tmp/t.sh && chmod +x /tmp/t.sh && /tmp/t.sh` 应输出 ok。
- `readlink -f $(which awk)` 应指向 `$PREFIX/bin/gawk` 或至少是 Termux 的 awk，不是 `/system/bin/awk`。
- `ls -l $PREFIX/lib/libcrypto.so $PREFIX/lib/libtermux-exec.so` 应存在。
- 在终端执行 `pkg install` 之外的 `git`/`make`/`npm` 小项目，确认 shebang 不再报错。

**置信度**：CONFIRMED（bootstrap 内容 + 代码）；运行时具体表现需设备确认。

---

### P1-3 Service/进程管理：Stop 不是真 Stop、主线程可能 ANR、孤儿进程、8080 冲突

这个问题可以拆成 4 个子问题，但根因都是“按单个 Process 管理、没有进程树/状态机”。

#### P1-3a `CodeServerLauncher.stop()` 在主线程 `waitFor(5s)`

- [`CodeServerLauncher.kt:125-136`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L125-L136)：`destroy()` 后 `waitFor(5, TimeUnit.SECONDS)`，再 `destroyForcibly()`。
- [`CodeServerService.kt:69-71`](app/src/main/java/com/devbox/CodeServerService.kt#L69-L71)：ACTION_STOP 在主线程直接 `stopServer()`。
- [`CodeServerService.kt:84-89`](app/src/main/java/com/devbox/CodeServerService.kt#L84-L89)：`onDestroy()` 也在主线程调用。
- 影响：点通知 Stop 或系统销毁 Service 时，主线程最多阻塞 5 秒；低端设备/繁忙时可能 ANR。
- 修复：`stop()` 改成异步：主线程只 `destroy()`，`waitFor`/强杀放到 `Dispatchers.IO`；ACTION_STOP 用 `scope.launch(Dispatchers.IO)` 后 `stopSelf()`。

#### P1-3b ACTION_STOP 不 `stopSelf()`，也不取消初始化

- [`CodeServerService.kt:69-72`](app/src/main/java/com/devbox/CodeServerService.kt#L69-L72)：ACTION_STOP → `stopServer()`，直接返回，没有 `stopSelf()`。
- [`CodeServerService.kt:221-226`](app/src/main/java/com/devbox/CodeServerService.kt#L221-L226)：只 `stopForeground` + 停 launcher，不取消 initJob/scope。
- 影响：首启 236MB 安装中点击 Stop，初始化协程继续下载/apt，稍后还可能启动 code-server；通知已消失，Service 变成后台 started service。运行中点 Stop，Service 仍存活，WebView 旧页面还在。
- 修复：ACTION_STOP 取消 initJob、`stopServer()`、`stopForeground(REMOVE)`、`stopSelf(startId)`；Activity 监听 STOPPED 显示“已停止/重启”。

#### P1-3c 孤儿 code-server/子进程

- [`CodeServerLauncher.kt:125-136`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L125-L136)：只对父进程 `destroy()`，不杀进程树。
- [`CodeServerService.kt:174-181`](app/src/main/java/com/devbox/CodeServerService.kt#L174-L181)：初始化异常只 `stopSelf()`，不先 `stopServer()`；若 code-server 已 spawn 但 `waitForServerReady()` 超时，子进程继续跑。
- [`App.kt:44-70`](app/src/main/java/com/devbox/App.kt#L44-L70)：崩溃处理器不清理子进程。
- 影响：app 进程崩溃/OOM 后 code-server/node/extension host 可能继续监听 8080；下次启动新进程绑定失败，健康检查却命中旧进程，显示 RUNNING。
- 修复：`setsid`/`ProcessHandle.descendants()` 管理进程树；启动失败/超时/stopSelf 前统一 `stopServer()`；写 PID 文件，下次启动先清理；`monitorJob` 用局部 `val process`，不要用共享可变字段。

#### P1-3d 固定 8080 + 只看 `/healthz` 200

- [`CodeServerLauncher.kt:25`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L25)：`PORT = 8080`。
- [`CodeServerLauncher.kt:138-154`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L138-L154)：`healthCheck()` 只判断 `responseCode == 200`；`waitForServerReady()` 不检查子进程是否活着。
- 影响：8080 被其他服务占用时，新 code-server 退出，但 `/healthz` 可能被旧进程/其他服务响应；WebView 加载“别人的服务”；或 60 秒后失败无重试。
- 修复：`ServerSocket(0)` 选空闲端口，实际 URL 存 `ServerStatus`；每轮检查 `serverProcess.isAlive`；启动前探测端口；若保留 8080，至少加备选端口。

**置信度**：CONFIRMED（代码）；ANR/孤儿表现需真机。

---

### P1-4 WebView 错误/重试/渲染进程/键盘：白屏和“键盘遮挡”风险

#### P1-4a 只用 deprecated `onReceivedError`，且不区分主框架

- [`MainActivity.kt:261-274`](app/src/main/java/com/devbox/MainActivity.kt#L261-L274)：旧签名回调，没有 `request.isForMainFrame`。
- [`MainActivity.kt:242-258`](app/src/main/java/com/devbox/MainActivity.kt#L242-L258)：`onPageFinished` 第一次就 `webViewReady = true`，错误页也可能触发。
- 影响：子资源失败可能每 2 秒 `loadUrl` 整页；多个错误叠加多个延时任务；错误页触发 `onPageFinished` 后 overlay 隐藏，用户看到 Chromium 错误页，之后不再自动重试；`webViewReady` 永不复位。
- 修复：重写 `onReceivedError(view, request, error)` + `onReceivedHttpError`，只处理 `isForMainFrame`；`onPageStarted` 重置状态；用可取消的 `retryJob`，指数退避，最多 3–5 次；错误时 overlay 显示 Retry/日志/退出，不要隐藏。

#### P1-4b 没有 `onRenderProcessGone`

- [`MainActivity.kt:225-276`](app/src/main/java/com/devbox/MainActivity.kt#L225-L276)：`WebViewClient` 未重写该回调。
- 影响：WebView renderer 被系统杀死（OOM/大文件/扩展）时，默认会带崩宿主 app 进程；Service 与 WebView 同进程，一起死。
- 修复：`onRenderProcessGone` 返回 true，销毁/重建 WebView，显示错误 overlay；更彻底是把 Service 放独立 `:server` 进程。

#### P1-4c URL 前缀匹配可绕过

- [`MainActivity.kt:232-239`](app/src/main/java/com/devbox/MainActivity.kt#L232-L239)：`url.startsWith("http://localhost")` / `startsWith("http://127.0.0.1")`。
- 影响：`http://localhost.evil.com`、`http://127.0.0.1.evil.com` 会被当成“本地”留在 WebView，而 JS bridge 暴露给所有 frame；恶意 workspace/网页可调用 `openExternalUrl`、`getHomeDirectory`、`showToast`。
- 修复：用 `Uri.parse(url)`，精确比较 `scheme == http && host in {"127.0.0.1","localhost"} && port == 8080`；更稳妥是移除 JS bridge（当前 code-server UI 并不依赖它），或改用 `WebViewCompat.addWebMessageListener` 并限制 origin。

#### P1-4d release 仍开启 WebView 调试、file access 过宽

- [`MainActivity.kt:210-212`](app/src/main/java/com/devbox/MainActivity.kt#L210-L212)：无条件 `setWebContentsDebuggingEnabled(true)`。
- [`MainActivity.kt:185-190`](app/src/main/java/com/devbox/MainActivity.kt#L185-L190)：`allowFileAccess`、`allowContentAccess`、`allowUniversalAccessFromFileURLs = true`。
- 影响：release 可通过 `chrome://inspect` 调试/执行页面 JS；file access 对 HTTP 页面并非必要，扩大攻击面。
- 修复：`setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`；不需要时关闭 file/content/universal access；`mixedContentMode` 改 `NEVER_ALLOW`。

#### P1-4e edge-to-edge + `adjustResize` 没有 IME insets

- [`MainActivity.kt:114`](app/src/main/java/com/devbox/MainActivity.kt#L114)：`setDecorFitsSystemWindows(window, false)`。
- [`MainActivity.kt:449-458`](app/src/main/java/com/devbox/MainActivity.kt#L449-L458)：只隐藏 system bars。
- [`AndroidManifest.xml:45`](app/src/main/AndroidManifest.xml#L45)：`windowSoftInputMode="adjustResize"`。
- 影响：软键盘弹出时 WebView 没有 `ime()` padding，编辑器/终端可能被键盘遮住；Android 15 edge-to-edge 下风险更高。
- 修复：`ViewCompat.setOnApplyWindowInsetsListener(webView)`，把 `WindowInsetsCompat.Type.ime()` 的 bottom 作为 padding；或在 API 30+ 关闭 edge-to-edge，改传统沉浸式。需要真机验证。

#### P1-4f 没有文件选择器 / 旧 WebView 版本提示

- 未实现 `onShowFileChooser`：VS Code 的“打开文件夹/文件”依赖 `<input type=file>`，WebView 默认不弹选择器。
- 没有 WebView 版本检查：Android 8/9 上旧 WebView 可能不支持现代 VS Code，白屏且无提示。
- 修复：实现文件选择器（SAF）并在 ROADMAP 里已经提到；用 `WebViewCompat.getCurrentWebViewPackage()` 检查版本，低于门槛提示更新 WebView；捕获 `MissingWebViewPackageException`。

**置信度**：代码路径 CONFIRMED；子资源回调、键盘遮挡、renderer 行为需真机/版本验证。

---

### P1-5 外部 HOME 选择不持久化，进程重启后用户文件“消失”

**位置**

- [`TermuxManager.kt:87-88`](app/src/main/java/com/devbox/TermuxManager.kt#L87-L88)：`_homeDir` 只在内存。
- [`TermuxManager.kt:217-221`](app/src/main/java/com/devbox/TermuxManager.kt#L217-L221)：`setHomeDir()` 不持久化。
- [`CodeServerService.kt:79`](app/src/main/java/com/devbox/CodeServerService.kt#L79)：`START_STICKY`。
- 全工程没有 `SharedPreferences`/`DataStore`/`onSaveInstanceState`。

**影响**

- 用户授权 `/sdcard/AMCode` 后，Service 因进程死亡被 `START_STICKY` 重启（null intent，Activity 还没重建）：`homeDir` 回到 `filesDir/home`，code-server 以内部目录启动。
- 用户再次打开 app，Activity 会 `setHomeDir(external)`，但 code-server 已在内部 HOME 运行；不会重启，用户看到“文件不见了”。
- “Use Internal Storage” 选择下次启动还会再问。
- 首启时 Service 在存储授权前启动，也会先用内部 HOME 启动；之后授权不会改变已运行服务的工作目录。

**修复**

- `SharedPreferences`/DataStore 保存 `homeMode` 和可选路径；`App.onCreate()` 或 Service `onCreate()` 先恢复。
- Service 启动时校验当前 HOME 与持久化选择一致；不一致重启 code-server。
- 首启必须等存储选择完成后再启动 Service（与 P0-2/P0-3 合并修）。

**置信度**：CONFIRMED（机制）；系统重启时机需设备。

---

### P1-6 发布与构建：Release APK 落后 HEAD、clean clone 无法构建 release、x86_64 资产缺失

**位置/证据**

- `git tag v1.0` = `fc57544`，HEAD = `2d9b486`；v1.0 之后还有 8 个提交（包括 `termux-keyring`、mirrors 排除、VHEditor 实验/回滚）。
- GitHub Release 发布于 2026-08-07，APK 大小 67,590,045 B；本地 release 构建于 2026-08-08 15:20，67,590,497 B。**用户从 Releases 下载的不是 HEAD 的构建。**
- [`app/build.gradle.kts:27-34`](app/build.gradle.kts#L27-L34)：release signingConfig 无条件创建，密码 `amcode123` 硬编码；`.gitignore:42` 忽略 `app/amcode-release.jks`。clean clone 实测：
  ```
  > Task :app:validateSigningRelease FAILED
  > Keystore file '...\app\amcode-release.jks' not found for signing config 'release'.
  ```
- [`app/build.gradle.kts:81-84`](app/build.gradle.kts#L81-L84)：只下载 aarch64 bootstrap；x86_64 资产是本地手工放进去的，clean clone 没有。
- [`app/build.gradle.kts:86-126`](app/build.gradle.kts#L86-L126)：下载失败只打印 warning 继续构建；直接写目标文件，只有 `>10MB` 的缓存判断，截断文件可能被当成合法缓存。
- [`app/build.gradle.kts:15`](app/build.gradle.kts#L15)：`targetSdk=28`；`versionCode=1` 固定。
- `README.md:99-108` 直接要求 `./gradlew assembleRelease`，没有 unsigned fallback。

**影响**

- 用户下载的 Release 可能仍带旧 bug；作者修了代码但没重新发版。
- 新贡献者按 README 构建 release 直接失败；x86_64 模拟器用户拿到的 APK 缺资产，首启 `FileNotFoundException`。
- 构建不可复现，CI 无法稳定产出。

**修复**

- Release 从 HEAD 重新构建并更新 tag/asset；提供 SHA-256。
- 签名改为 `keystore.properties`/CI secret；无 key 时允许 unsigned release 或明确报错。
- `downloadBootstrap` 同时处理 aarch64/x86_64（以及 i686 要么补资产要么删除分支），失败即构建失败；临时文件 + rename + 固定 SHA-256；不要写进 `src/main/assets`。
- `versionCode` 由 Gradle property/CI 注入；README 写清楚 keystore、SDK、x86 资产。

**置信度**：CONFIRMED（git tag/release + build agent 实测）。

---

### P1-7 扩展与 Marketplace 的承诺不成立

**位置/文档**

- [`README.md:46`](README.md#L46)、[`README.md:149`](README.md#L149)：“Extensions work normally / VS Code marketplace 正常使用”。
- code-server 官方 Termux 文档：Android 被当成 unsupported platform，**许多扩展（包括语言包）无法安装**；只允许 Web Extensions。官方 workaround 是 `NODE_OPTIONS=--require android-as-linux.js`，并警告 native 依赖可能出问题。
- code-server FAQ：不能使用 Microsoft Marketplace，通常用 Open VSX（或自建 gallery）。
- git 历史：`e817253` 加过 `NODE_OPTIONS` + `android-as-linux.js`，`736a470` 试过 LD_PRELOAD，`13c0f0f` 全部回滚。HEAD 没有任何 workaround。

**影响**

- 用户以为能装 Pylance/C#/语言包等，实际很多会失败或被拒。
- 纯 Web 扩展、部分 JS 扩展可能可用；native 扩展、依赖 `process.platform === 'linux'` 的扩展大概率失败。
- README/Release notes 的“完整扩展市场”是过度承诺。

**修复**

- 文档改为 **Open VSX**，明确“仅部分扩展可用；native/平台相关扩展可能失败”；给出 VSIX 手动安装方法。
- 可选：重新加入官方 `android-as-linux.js` + `NODE_OPTIONS` 作为**实验开关**，并做扩展兼容性测试；不要默认全开。
- 如果“完整扩展”是硬需求，考虑 VHEditor 式 patched code-server/VS Code，或 CodeFA 式 proot Ubuntu；native 方案无法完全解决。

**置信度**：CONFIRMED（官方文档 + git 历史）。

**模拟器实测补充**：在 API 34 x86_64 上，code-server 的 WebView UI 能打开，但 extension host 报 `spdlog.node` 的 libc++ 符号不兼容和 `ripgrep` 路径 ENOENT；这说明“仅部分扩展可用”不仅取决于 `process.platform`，还取决于 TUR 包 native 模块与 bootstrap 的 ABI 匹配。x86_64 模拟器上尤其明显，arm64 真机需再确认。

---

### P1-8 网络/安装失败没有用户可操作的恢复入口

- `isReady()` 死路（P0-4）。
- `showError()` 只显示文本 + Toast，没有 Retry/清数据/导出日志（[`MainActivity.kt:432-439`](app/src/main/java/com/devbox/MainActivity.kt#L432-L439)）。
- `onServerReady` 15s+10s 后无条件隐藏 overlay，可能露出白屏（[`MainActivity.kt:408-430`](app/src/main/java/com/devbox/MainActivity.kt#L408-L430)）。
- apt 输出把 `W: Failed to fetch` 等过滤掉（[`TermuxManager.kt:671-684`](app/src/main/java/com/devbox/TermuxManager.kt#L671-L684)），用户看不到真实原因。
- 首启没有“将下载约 236MB”的提示（ROADMAP 自己列了）。
- 修复：错误 overlay 加 Retry / 重新安装 / 打开日志 / 退出；记录 apt 原始日志到文件；下载前显示大小/网络要求；失败后清理半成品并允许重试。

---

### P1-9 健康检查重启计数永远不重置，重复健康检查会并发重启

- [`CodeServerService.kt:185-219`](app/src/main/java/com/devbox/CodeServerService.kt#L185-L219)：`restartCount` 只在 `healthy && state != RUNNING` 时清零；但重启成功后第 207 行立刻把状态设为 RUNNING，所以下一轮条件永远为 false。
- 结果：累计 10 次崩溃后报 “Too many crashes”，不是“连续 10 次”。
- `startHealthChecks()` 每次初始化都覆盖 `healthCheckJob`，旧 Job 没 cancel；重复初始化（P0-2）会多个 Job 并发 stop/start。
- 修复：重启成功后立即 `restartCount = 0`；`startHealthChecks()` 先 cancel 旧 Job；失败重试用指数退避；达到上限设置 ERROR 并提供 Retry。

---

### P1-10 Android 12+ phantom process killer / 无 wake lock，长时间安装或构建容易被杀

**位置/证据**

- [`AndroidManifest.xml:14`](app/src/main/AndroidManifest.xml#L14) 声明了 `WAKE_LOCK`，但全工程没有 `PowerManager`/`WakeLock` 使用（grep 为空）。
- Termux README 明确警告：Android 12+ 的 phantom process killer 会杀超过 32 个进程或高 CPU 的子进程（`[Process completed (signal 9)]`，termux-app issue #2366）。code-server = node + extension host + ripgrep + 多个 shell + 终端，正好容易命中。
- 首次安装 236MB、解压 79MB、patch 数分钟、apt/dpkg 多进程，全程没有 partial wake lock，也没有电池优化白名单引导。
- 设备是 vivo/Android 16（崩溃日志），OEM 后台清理更激进。

**影响**

- 安装到一半被系统杀进程 → 触发 P0-4 的永久 brick。
- 后台构建/下载时服务被杀 → code-server/终端中断；用户以为 app 崩了。
- `WAKE_LOCK` 声明了却不用，既误导用户也过不了“权限最小化”审查。

**修复**

- 安装/首次启动期间持有 `PARTIAL_WAKE_LOCK`（带超时，结束释放）；或至少保持前台服务 + `setForegroundServiceBehavior`。
- 引导用户把 app 加入电池优化白名单（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 或设置页跳转），不要静默要求。
- 文档给出 Android 12+ phantom 限制的 ADB 关闭命令（高级用户可选）。
- 如果用不到 `WAKE_LOCK`，就删掉权限声明，别“声明了不用”。

**置信度**：机制 CONFIRMED（Termux 文档/issue + 代码无 wake lock）；具体触发频率需真机长任务测试。

---

### P1-11 可观测性不足：code-server 日志只进 Logcat，崩溃日志用户拿不到

- [`CodeServerLauncher.kt:102-116`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L102-L116)：code-server stdout/stderr 只 `Log.d`，没有持久化文件；用户无法导出。
- [`App.kt:26-28`](app/src/main/java/com/devbox/App.kt#L26-L28)：崩溃日志写 `getExternalFilesDir(null)/vsboxed_crash.log`；Android 11+ 的 `Android/data` 对普通文件管理器基本不可见，`getExternalFilesDir` 还可能为 null。
- [`TermuxManager.kt:671-684`](app/src/main/java/com/devbox/TermuxManager.kt#L671-L684)：过滤掉 `Bad system call`、`Errors were encountered`、`Failed to fetch`、GPG 错误等真实失败信息；apt/dpkg 退出码也被忽略。
- 结果：用户只会看到 “Failed: ...”，作者也无法远程诊断；这正是“时间久了记不清有什么 bug”的工程原因之一。
- 修复：code-server 输出写轮转日志；安装日志单独文件；错误 UI 提供“导出日志/复制”；保留退出码和最后 N 行；崩溃日志写到应用内可分享目录或通过 FileProvider 分享。

---

### P1-12 运行时版本漂移：TUR 拉最新 code-server，`nodejs-24` 硬编码

- TUR 当前 `code-server 4.143.1`（102.5MB，依赖 `libandroid-spawn/libsecret/krb5/nodejs-24/ripgrep`），ROADMAP 还写 4.131.0。
- [`TermuxManager.kt:585-591`](app/src/main/java/com/devbox/TermuxManager.kt#L585-L591)：`opt/nodejs-24/bin/node` 硬编码；TUR 换 nodejs-25/22 时，`bin/node` 可能仍由包创建，但 `lib/code-server/lib/node` 的修补会跳过，code-server 启动可能失败。
- TUR #2974 就是 native 模块不兼容导致 terminal 打不开；`apt install code-server` 永远拿最新，无法复现旧版本。
- 修复：固定 code-server/node 的 .deb 版本 + SHA256（VHEditor 做法），或下载固定 tarball；App 内显示版本；更新走显式操作；`nodejs-*` 用 glob 探测而不是硬编码 24。

**置信度**：版本漂移 CONFIRMED；具体 node 升级破坏需 TUR 未来验证。

---

## 4. P2/P3：值得排期但不必阻塞首修的问题

| 优先级 | 问题 | 位置/证据 | 建议 |
|---|---|---|---|
| P2 | 镜像测速 pipeline 退出码来自 `head`，失败 curl 也可能被选中 | [`TermuxManager.kt:448-477`](app/src/main/java/com/devbox/TermuxManager.kt#L448-L477) | 用 `curl -o /dev/null -w '%{http_code}'` 或 Kotlin HttpURLConnection，校验 200；并行测速 |
| P2 | `pkg` 会重写 `sources.list` 去掉 `trusted=yes`，而 keyring 的 8 个 `trusted.gpg.d` 链接缺失 → `pkg update` 可能 NO_PUBKEY | bootstrap `SYMLINKS.txt`、`bin/pkg` | 装好 keyring 真实文件/链接，或让 `pkg` 保留 `trusted=yes`；修 `pkg` cache 路径 |
| P2 | apt `update` 被调用两次（首次白做） | [`TermuxManager.kt:551`](app/src/main/java/com/devbox/TermuxManager.kt#L551)、[`TermuxManager.kt:560`](app/src/main/java/com/devbox/TermuxManager.kt#L560) | 加 TUR 源后只 update 一次 |
| P2 | 安装后对 `bin`+`lib` 递归 `grep -rl`/`sed -i`，`lib/code-server` ~540MB，可能耗时数分钟并复制大文件 | [`TermuxManager.kt:578-582`](app/src/main/java/com/devbox/TermuxManager.kt#L578-L582) | 只 patch 必要目录/精确文件；合并到安装阶段 |
| P2 | bootstrap 没有 `home/` 条目，app 也不写 `.bashrc`；code-server 终端是非 login bash，`etc/profile`/`bash.bashrc` 不会 source | [`TermuxManager.kt:287-314`](app/src/main/java/com/devbox/TermuxManager.kt#L287-L314) | 首次启动生成 `.bashrc`/`.profile`，或走 `$PREFIX/bin/login` |
| P2 | 没有 Termux 风格 extra-keys（Esc/Tab/Ctrl/方向键），软键盘下 `Ctrl+\`` 实际不可用；未写 code-server 官方建议的 `"keyboard.dispatch": "keyCode"` | 未实现 | 加 extra-keys 行/工具栏；写入默认 settings |
| P2 | 未声明 `android:installLocation="internalOnly"`；Termux 这类 app 不建议装到外部 | [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) | 加 `internalOnly` |
| P2 | `code-server` 运行时从 TUR 拉最新，ROADMAP 写 4.131.0 已过期；TUR 当前 4.143.1 | [`TermuxManager.kt:557-564`](app/src/main/java/com/devbox/TermuxManager.kt#L557-L564) | 固定版本 + SHA256；显示版本 |
| P2 | `bin/pkg` 的 cache 路径是 `/data/data/com.termux/cache/apt/...`，与 apt 实际 `$PREFIX/var/cache/apt` 不一致 | bootstrap `bin/pkg` | 修 `pkg` 脚本或换 wrapper |
| P2 | `trusted=yes` 同时关闭主仓与 TUR 的 GPG 校验 | [`TermuxManager.kt:475`](app/src/main/java/com/devbox/TermuxManager.kt#L475)、[`TermuxManager.kt:557-558`](app/src/main/java/com/devbox/TermuxManager.kt#L557-L558) | 装 keyring 后去掉 trusted；或固定版本+SHA256 |
| P2 | 固定 8080 + 健康检查只看 200 | [`CodeServerLauncher.kt:25`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L25)、[`CodeServerLauncher.kt:138-154`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L138-L154) | 随机端口 + 进程存活 + 服务身份校验 |
| P2 | `stop()` 主线程 `waitFor(5s)` | [`CodeServerLauncher.kt:125-136`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L125-L136) | 异步停止 |
| P2 | `onDestroy` 主线程 stop | [`CodeServerService.kt:84-89`](app/src/main/java/com/devbox/CodeServerService.kt#L84-L89) | 只发信号，后台清理 |
| P2 | `serverCollector` 在 `prepareAndStart()` 后立刻 cancel，状态可能仍为 STARTING，白等 10s 健康检查 | [`CodeServerService.kt:134-164`](app/src/main/java/com/devbox/CodeServerService.kt#L134-L164) | prepareAndStart 成功后显式设 RUNNING，或等 collector 收到 RUNNING |
| P2 | `monitorJob` 使用共享 `serverProcess`，stop/restart 时可能 NPE/状态错乱 | [`CodeServerLauncher.kt:102-116`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L102-L116) | 捕获局部 `val process`；start/stop 加锁 |
| P2 | `getPid` 反射私有字段，未来 Android 可能失效 | [`CodeServerLauncher.kt:156-158`](app/src/main/java/com/devbox/CodeServerLauncher.kt#L156-L158) | 用 `Process.pid()`（API 26+）或包装脚本写 PID |
| P2 | WebView 渲染进程崩溃无处理 | [`MainActivity.kt:225-276`](app/src/main/java/com/devbox/MainActivity.kt#L225-L276) | 实现 `onRenderProcessGone`；Service 独立进程 |
| P2 | release 仍开 WebView 调试 | [`MainActivity.kt:210-212`](app/src/main/java/com/devbox/MainActivity.kt#L210-L212) | `BuildConfig.DEBUG` |
| P2 | URL 前缀匹配绕过 | [`MainActivity.kt:232-239`](app/src/main/java/com/devbox/MainActivity.kt#L232-L239) | 精确解析 host/port |
| P2 | JS bridge 暴露给所有 frame，且当前 UI 未使用 | [`MainActivity.kt:222-223`](app/src/main/java/com/devbox/MainActivity.kt#L222-L223)、[`WebViewInterface.kt`](app/src/main/java/com/devbox/WebViewInterface.kt) | 删除或 `addWebMessageListener` + origin 限制 |
| P2 | `allowFileAccess`/`allowUniversalAccessFromFileURLs` 过宽 | [`MainActivity.kt:185-190`](app/src/main/java/com/devbox/MainActivity.kt#L185-L190) | 先验证 code-server 是否真的需要；不需要就关 |
| P2 | 每次 onCreate `clearCache(false)` | [`MainActivity.kt:220`](app/src/main/java/com/devbox/MainActivity.kt#L220) | 去掉；只在版本升级时清 |
| P2 | 没有 `onShowFileChooser` | 未实现 | 实现 SAF 文件/文件夹选择；ROADMAP 已列 |
| P2 | 没有 WebView 版本检查 | 未实现 | 检查 `WebViewCompat.getCurrentWebViewPackage`，低版本提示更新 |
| P2 | `configChanges` 缺 `uiMode/fontScale/density/locale` | [`AndroidManifest.xml:42`](app/src/main/AndroidManifest.xml#L42) | 补齐或把状态放 Service/ViewModel |
| P2 | `START_STICKY` + HOME 不持久化 | [`CodeServerService.kt:79`](app/src/main/java/com/devbox/CodeServerService.kt#L79) | 持久化 homeMode；重启时恢复 |
| P2 | `ServiceConnection` 重复 bind/collector 泄漏 | [`MainActivity.kt:69-97`](app/src/main/java/com/devbox/MainActivity.kt#L69-L97) | 保存 collector Job；bind/unbind 配对；`onBindingDied` |
| P2 | `storageResolved` 死状态 | [`MainActivity.kt:319`](app/src/main/java/com/devbox/MainActivity.kt#L319) | 用它做单次流程，或删除 |
| P2 | Auto Backup 包含 32MB bootstrap 和内部 home | [`AndroidManifest.xml:29-30`](app/src/main/AndroidManifest.xml#L29-L30)、[`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml) | 排除 `files/bootstrap-*.zip`、`files/home`；补 Android 12 `dataExtractionRules` |
| P2 | `WAKE_LOCK`/`ACCESS_NETWORK_STATE` 未使用 | [`AndroidManifest.xml:7`](app/src/main/AndroidManifest.xml#L7)、[`AndroidManifest.xml:14`](app/src/main/AndroidManifest.xml#L14) | 删除或真正使用 |
| P2 | 1.8MB 2048×2048 launcher PNG | [`ic_foreground.png`](app/src/main/res/drawable/ic_foreground.png) | 432×432 / WebP / vector；省 ~1.5MB |
| P2 | Material 依赖仅剩 857 类/96KB，源码 0 引用 | [`app/build.gradle.kts:143`](app/build.gradle.kts#L143) | 删除 material |
| P2 | `viewBinding=true` 未使用 | [`app/build.gradle.kts:61-63`](app/build.gradle.kts#L61-L63) | 关闭 |
| P2 | `androidx.webkit`、`lifecycle-service` 未使用 | [`app/build.gradle.kts:137-140`](app/build.gradle.kts#L137-L140) | 删除；webkit 若做版本检查可保留 |
| P2 | R8 `-keep class com.devbox.** { *; }` 使 app 代码基本不混淆/不裁剪 | [`proguard-rules.pro:3`](app/proguard-rules.pro#L3) | 删除 broad keep，保留 `@JavascriptInterface` 规则 |
| P2 | `versionCode=1` 固定 | [`app/build.gradle.kts:15`](app/build.gradle.kts#L15) | property/CI 注入 |
| P2 | `targetSdk=28` 不能上 Play，且 lint 被禁用 | [`app/build.gradle.kts:15`](app/build.gradle.kts#L15)、[`app/build.gradle.kts:23-25`](app/build.gradle.kts#L23-L25) | 明确 sideload/F-Droid 定位；不要只改数字，会破坏 W^X |
| P2 | 没有 CI/测试 | 仓库无 `.github/workflows`、`app/src/test` | 加 build/lint/unit test/APK 检查 |
| P2 | 首次 236MB 下载无进度/无恢复/无取消 | [`TermuxManager.kt:175-194`](app/src/main/java/com/devbox/TermuxManager.kt#L175-L194) | 解析 apt 输出/显示 MB；支持重试/取消；检查网络 |
| P2 | 临时 bootstrap zip 30MB 不删除；apt cache 236MB 不清理 | [`TermuxManager.kt:132-155`](app/src/main/java/com/devbox/TermuxManager.kt#L132-L155) | 安装成功后删除 temp zip；可选 `apt clean` |
| P2 | `cp -r` 搬 ~1GB 文件，峰值空间和耗时翻倍 | [`TermuxManager.kt:575-577`](app/src/main/java/com/devbox/TermuxManager.kt#L575-L577) | 用 `mv`（同一文件系统） |
| P2 | `dpkg -i *.deb` 输出不读取，可能管道阻塞 | [`TermuxManager.kt:570-571`](app/src/main/java/com/devbox/TermuxManager.kt#L570-L571) | 后台读输出或重定向到日志 |
| P2 | `dpkg` 命令退出码被忽略，`isNodeInstalled` 只看文件存在 | [`TermuxManager.kt:570-571`](app/src/main/java/com/devbox/TermuxManager.kt#L570-L571)、[`TermuxManager.kt:657-658`](app/src/main/java/com/devbox/TermuxManager.kt#L657-L658) | 检查退出码 + 实际执行 `node -v`/`code-server --version` |
| P2 | `nodejs-24` 路径硬编码，TUR 升版可能失效 | [`TermuxManager.kt:585-591`](app/src/main/java/com/devbox/TermuxManager.kt#L585-L591) | 从 `bin/node`/`opt/nodejs-*` 探测，或用 package 自带 symlink |
| P2 | `tryInstall` 内层只重试 1 次，外层 3 次，进度文案不一致 | [`TermuxManager.kt:544-603`](app/src/main/java/com/devbox/TermuxManager.kt#L544-L603) | 统一重试策略/文案 |
| P2 | `bootstrapAsset()` 对 armeabi-v7a 回退到 aarch64，i686 资产不存在 | [`TermuxManager.kt:41-48`](app/src/main/java/com/devbox/TermuxManager.kt#L41-L48) | manifest 限制 ABI，或补资产/明确报错 |
| P2 | `bootstrapZip` 固定 aarch64 文件名 | [`TermuxManager.kt:132`](app/src/main/java/com/devbox/TermuxManager.kt#L132) | 按 ABI 命名 |
| P2 | 没有 bootstrap SHA-256，资产可被替换/截断 | [`app/build.gradle.kts:83`](app/build.gradle.kts#L83) | 固定 hash，构建/运行时校验 |
| P2 | `downloadBootstrap` 写 `src/main/assets`，非 hermetic | [`app/build.gradle.kts:81-129`](app/build.gradle.kts#L81-L129) | 改 build/generated + declared outputs |
| P2 | Gradle wrapper 无 `distributionSha256Sum`，只指腾讯镜像 | [`gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties) | 加 checksum/官方源 fallback |
| P2 | `README` 的扩展/市场/Android 8+/pkg 承诺不实 | README 多处 | 按事实重写 |
| P2 | `ARCHITECTURE` 的“0.0.0.0 只 localhost”错误 | [`ARCHITECTURE.md:188-189`](ARCHITECTURE.md#L188-L189) | 修正 |
| P3 | `dispatchKeyEvent` 两个分支相同，无实际作用 | [`MainActivity.kt:302-310`](app/src/main/java/com/devbox/MainActivity.kt#L302-L310) | 删除或实现快捷键 |
| P3 | `runDpkgInstall()`/`runNpmInstall()` 死代码 | [`TermuxManager.kt:606-655`](app/src/main/java/com/devbox/TermuxManager.kt#L606-L655) | 删除 |
| P3 | `onPause` 空实现 | [`MainActivity.kt:471-474`](app/src/main/java/com/devbox/MainActivity.kt#L471-L474) | 删除 |
| P3 | `webView.url == null` 几乎不成立 | [`MainActivity.kt:466-468`](app/src/main/java/com/devbox/MainActivity.kt#L466-L468) | 删除或改成连接状态检查 |
| P3 | `cpp/CMakeLists.txt` + `platform_fix.c` 未接入构建 | `app/src/main/cpp/` | 删除（native 实验遗留） |
| P3 | `packaging.jniLibs.useLegacyPackaging` 对无 native lib 的包无意义 | [`app/build.gradle.kts:69-71`](app/build.gradle.kts#L69-L71) | 删除 |
| P3 | `aaptOptions` deprecated | [`app/build.gradle.kts:74-76`](app/build.gradle.kts#L74-L76) | 改 `androidResources.noCompress` |
| P3 | `init.gradle.kts` 不会被自动应用 | `init.gradle.kts` | 删除或写进 settings |
| P3 | `scripts/bootstrap_setup.sh` 过时（2024 URL/旧流程） | `scripts/bootstrap_setup.sh` | 删除或标注 |
| P3 | `DEVELOPMENT.md` 被 gitignore，fresh clone 没有 | `.gitignore:43` | 移到 docs/ 或取消忽略 |
| P3 | 品牌残留 VSBoxed | `App.TAG`、`CHANNEL_ID`、`vsboxed_crash.log`、`Theme.VSBoxed`、`JS_NAME`、`rootProject.name` | 统一 AMCode（channel id 可保留兼容） |
| P3 | i18n：只有存储对话框按 Locale，其他硬编码英文 | MainActivity/Service | 全部入 `strings.xml` + `values-zh` |
| P3 | `getAppVersion()` 硬编码 1.0.0 | [`WebViewInterface.kt:37-40`](app/src/main/java/com/devbox/WebViewInterface.kt#L37-L40) | 用 `BuildConfig.VERSION_NAME` |
| P3 | `setReadable/setExecutable(true,false)` 世界可读/可执行 | [`TermuxManager.kt:352`](app/src/main/java/com/devbox/TermuxManager.kt#L352) | 改 owner-only；lint `SetWorldReadable` |
| P3 | crash log 写 `getExternalFilesDir`，可能 null/用户难访问 | [`App.kt:26-28`](app/src/main/java/com/devbox/App.kt#L26-L28) | 处理 null；写到可分享目录或应用内页面 |
| P3 | `getExternalFilesDir` 为 null 时 File(null) NPE 被 catch，日志丢失 | 同上 | 判空 |
| P3 | `setSupportMultipleWindows(false)` 可能让 OAuth popup 失败 | [`MainActivity.kt:189`](app/src/main/java/com/devbox/MainActivity.kt#L189) | 评估；需要时实现 `onCreateWindow` |
| P3 | `mediaPlaybackRequiresUserGesture=false`、`builtInZoomControls=true` 等移动端设置可精简 | [`MainActivity.kt:196-207`](app/src/main/java/com/devbox/MainActivity.kt#L196-L207) | 按实际需求调整 |
| P3 | `allowBackup` + 未排除 bootstrap/home | 见 P2 表 | 修 backup 规则 |
| P3 | Lint 22 warnings（SdCardPath/UnusedResources/IconLocation 等） | `app/build/reports/lint-results-debug.*` | 逐项清理 |
| P3 | 没有 `roundIcon`/monochrome adaptive icon | manifest/mipmap | 补 |

---

## 5. 优化路线图

### 5.1 半天内可做的 Quick Wins

1. `--bind-addr 127.0.0.1`（先堵住 LAN；auth 稍后）。
2. `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`。
3. `MainActivity` 加 `started` 标志，删除重复 `startCodeServerService()`（或让权限流程只在一个入口结束）。
4. Android 26–29 请求 `WRITE_EXTERNAL_STORAGE`；`setHomeDir` 检查 `mkdirs`。
5. `ACTION_STOP` 加 `stopSelf()`/取消 init；`stop()` 改异步。
6. `onReceivedError` 换新签名 + `isForMainFrame`；错误 overlay 加 Retry。
7. 删除 `addJavascriptInterface`（如果确认 UI 不用）；URL 改精确 host 判断。
8. 删除 Material/webkit/lifecycle-service/viewBinding/dead cpp；图标转 WebP/缩小。
9. 安装成功后删除 `filesDir/bootstrap-*.zip`；`cp -r` 改 `mv`。
10. Release 从 HEAD 重建，更新 tag/asset。

预计收益：安全面大幅下降、首启竞态消失、旧 Android 可启动、APK 减 ~2MB、错误可恢复。

### 5.2 1–2 周内应做的 P1

1. Service `initJob`/Mutex + Activity 权限状态机 + `configChanges` 补齐。
2. `.amcode-ready` 完成标记 + 半成品恢复 + bootstrap checksum/清理。
3. 持久化 `homeMode`；Service 重启恢复；首启等权限完成再启动。
4. `termux-exec` + `SYMLINKS.txt` 解析；`LD_PRELOAD`；smoke test。
5. 随机端口 + 进程存活 + 进程树清理 + PID 文件。
6. WebView `onRenderProcessGone`、`onShowFileChooser`、WebView 版本检查、IME insets。
7. 内部 HOME 默认或双模式；文档说明 `/sdcard` 限制。
8. 下载进度/ETA/重试/取消；错误日志。
9. `pkg install` 方案（至少先改文档，最好做 `.deb` rewrite）。
10. CI：assembleDebug + lint + unit test + fresh clone release；x86_64 资产。

### 5.3 1–2 个月的架构级优化

1. **APK/资产**：ABI split 或首启按 ABI 下载 bootstrap；`arm64` APK 可降到 ~35MB；离线版保留双资产。
2. **bootstrap 预 patch**：在构建机完成 `com.termux → com.devbox` 和 symlink 清单，设备端只解压，首启快很多。
3. **包管理**：改 `.deb` data.tar 路径，或自建 prefix 仓库；让 `pkg install` 真正可用。
4. **扩展**：实验性 `android-as-linux.js` + VSIX 手动安装；或 fork code-server/VHEditor 路线。
5. **Service 进程分离**：`:server` 进程跑 code-server，WebView/Activity 崩溃不杀服务；WebView renderer 崩溃可恢复。
6. **可观测性**：结构化日志、崩溃上报（可选本地）、安装阶段耗时埋点。
7. **测试**：API 26/28/29/30/33/34/35/36、arm64/x86_64、权限拒绝、断网、杀进程、端口冲突、旋转/键盘、LAN 扫描。
8. **发布**：GitHub Actions 构建 + SHA-256 + 版本号；F-Droid/自发布（targetSdk 28 + MANAGE_EXTERNAL_STORAGE 无法上 Play）。

---

## 6. 与其他开源项目对比

| 项目 | 方案 | 优点 | 代价 | 对 AMCode 的启示 |
|---|---|---|---|---|
| **Termux** | 原生 bionic + 完整 bootstrap/termux-exec/包管理 | 成熟、包多、终端强 | 不是 IDE；需要用户自己装 code-server | 直接复用它的 `SYMLINKS.txt` 解析、`termux-exec`、bootstrap second-stage、包管理思路 |
| **CodeFA (code_lfa)** | Flutter + proot Ubuntu + code-server | 真 Linux，pkg/扩展/Git 兼容性最好 | 体积/内存/启动开销；proot 性能 | 如果“完整 VS Code + 3000 包”是硬需求，这是更现实的路线 |
| **VHEditor-Android** | 原生 + patched code-server/VS Code（伪装 Linux） | 扩展兼容性比 stock code-server 好 | 需要 fork/维护 code-server；构建复杂 | AMCode 已试过 NODE_OPTIONS/LD_PRELOAD 但回滚；若要扩展，需 VHEditor 式源码 patch |
| **Acode** | 原生 Android 编辑器 | 移动端 UX 好、轻量 | 不是 VS Code，扩展生态不同 | 如果目标是手机编辑体验，原生编辑器可能比“塞 VS Code”更合适 |
| **code-server 官方 Termux 文档** | Termux + TUR code-server | 官方支持路径 | 明确限制：/sdcard Git 不工作、很多扩展失败 | AMCode 应把限制写进 README，而不是承诺“正常” |
| **code-server FAQ** | — | 说明不能用 MS Marketplace | Open VSX/自建 gallery | README 的“VS Code marketplace”应改 |

**结论**：AMCode 的 native 路线不是错，但它适合“轻量、快速、预装工具链的 VS Code 编辑器”，不适合“完整 Linux 开发环境 + 全扩展市场”。如果后者是产品目标，建议认真评估 CodeFA 的 proot 路线或 VHEditor 的 patched code-server 路线；如果坚持 native，就把承诺收窄、把 P0/P1 修扎实。

补充几个上游事实，便于判断“该抄谁”：

- **扩展问题的机制**：VS Code 的 `platform.ts` 在原生 Node 下 `isWeb=false`，但 `Platform` 枚举没有 `android`，不匹配 win32/darwin/linux 时默认落到 `Platform.Web`；所以 gallery/扩展类型判定把 Android 当 Web，只允许 Web 扩展。官方 Termux 文档给出的 workaround 正是 `android-as-linux.js` + `NODE_OPTIONS`，并警告 native 依赖可能出问题。本仓库 `e817253` 实现过、`736a470` 改成无效的 `uname()` hook、`13c0f0f` 全删；**回滚确实重新引入了扩展问题**。
- **CodeFA 并不完美**：它同样有 `0.0.0.0 + auth none` 和过多权限的问题；可以学它的 proot/完整 Linux 兼容性，但不要照抄安全配置。
- **VHEditor 的工程化**：自建 Android Node + patched code-server/VS Code（`node-preload.so`、patches）、bootstrap/code-server 都有 SHA256、ABI splits、mobile display mode/virtual mouse/tab 修复；这是“完整 VS Code”路线，但维护成本是 L 级。
- **Play 分发**：targetSdk 28 是 W^X 的合理取舍，Android 14/15 仍可安装（作者 Android 16 真机日志也证明），但 Google Play 2026-08-31 起要求 API 36，且 `MANAGE_EXTERNAL_STORAGE` 只允许文件管理器/备份/杀毒/文档类 app；AMCode 只能 sideload/F-Droid。
- **16 KB 是好消息**：APK 本身无 native lib；bootstrap 里 338 个 ELF 经校验全部满足 16KB `p_align`/`p_vaddr` 对齐，对 Android 15/16 的 16KB page size 设备友好。TUR 后续安装的包未逐一验证，建议在 16KB 模拟器上跑一次。
- **`com.devbox` 不是唯一选择**：`com.amcode` 也正好 10 字符，若未来想统一品牌，趁用户少时迁移代价最低；但改包名会改变 app 身份/签名/数据目录，必须做迁移方案，不要直接改。

---

## 7. README / ARCHITECTURE 事实核查

| # | 文档声明 | 代码事实 | 结论 |
|---|---|---|---|
| 1 | “首次启动会询问存储权限”（README:20,123；ARCH:138） | API 26–29 直接 `setHomeDir`，没有运行时请求（MainActivity:330-335） | ❌ Android 8/9/10 不询问且会失败 |
| 2 | “`--auth none` … only accessible on localhost”（ARCH:185） | 实际 `--bind-addr 0.0.0.0:8080`（CodeServerLauncher:85-87） | ❌ 局域网可访问且无密码 |
| 3 | “0.0.0.0 bind … only localhost reachable”（ARCH:188-189） | 同一文档第 122 行又写 0.0.0.0；Android 无默认入站防火墙 | ❌ 自相矛盾 |
| 4 | “自动测速三个镜像选最快”（README:59,162；ARCH:106） | pipeline 退出码来自 `head`，失败 curl 也算成功（TermuxManager:463-470） | ❌ 可能选中最先失败的镜像 |
| 5 | “x86_64 bootstrap 由构建下载”（README:53,156；ARCH:154） | Gradle 只下载 aarch64；x86_64 是 gitignored 本地文件（build.gradle:81-84） | ❌ fresh clone x86_64 不可用 |
| 6 | “失败重试 3 次；Packages are verified”（ARCH:94；Service:131） | 重试只在单次 initialize；isReady 提前返回后永不重试 | ⚠️ 误导，一次网络失败可能永久失败 |
| 7 | “`--force-all` 跳过 maintainer scripts”（ARCH:77） | `--force-all` 不跳过脚本；脚本仍执行并在 seccomp 下失败，只是 dpkg 继续 | ❌ 机制描述错误 |
| 8 | “最多 10 次连续崩溃后放弃”（ARCH:131） | 计数重置条件不可达，实际是生命周期累计 10 次 | ❌ “consecutive”不成立 |
| 9 | “bootstrap 构建时自动下载”（README:108） | 只下载 aarch64；失败只 warning；运行时无下载 fallback | ⚠️ 部分虚假 |
| 10 | “扩展市场正常使用”（README:46,149） | code-server 用 Open VSX；Android 上很多扩展/语言包失败 | ❌ 过度承诺 |
| 11 | “网络仅首次启动需要，之后完全离线”（README:55,158） | 编辑器可离线，但 pkg/扩展/Git 远端需要网络 | ⚠️ 过度承诺 |
| 12 | “`./gradlew assembleRelease` 即可构建”（README:104） | release 依赖 gitignored JKS；clean clone 失败 | ❌ fresh clone 失败 |
| 13 | “337+ 个二进制被 patch”（ARCH:88） | 代码只记录 patchedCount，没有固定 337 | ⚠️ 未证实/可能过期 |
| 14 | “START_STICKY 自动恢复可靠”（ARCH:132） | null intent + HOME 不持久化 + 孤儿进程，可能用错目录/端口 | ⚠️ 字面成立，可靠性误导 |
| 15 | “pkg/apt 3000+ 包可用”（README/Release notes） | .deb prefix 不匹配，后续 pkg install 装不进（P0-5） | ❌ 核心承诺不成立 |

---

## 8. 验证矩阵与验收标准

### 8.1 必测设备/场景

| 维度 | 覆盖 |
|---|---|
| Android | 8.0/9/10（API 26/28/29）、11（30）、13（33）、14（34）、15/16（35/36） |
| ABI | arm64-v8a 真机、x86_64 模拟器 |
| 存储 | 授权 external / 选 internal / 拒绝 / 中途撤销 / OEM 设置页缺失 |
| 网络 | 正常 / 慢速 / 断网 / 10%/50%/90% 中断 / TUR 不可达 / 镜像部分失败 |
| 生命周期 | 首启杀进程 / 转屏 / 深色模式 / 字体大小 / 分屏 / 后台 10 分钟 / 点通知 Stop |
| 端口 | 8080 被占用 / 旧 code-server 残留 |
| WebView | 旧 WebView / renderer 被杀 / 键盘弹出 / iframe 子资源失败 / 外链 |
| 安全 | 同网 PC 访问 8080 / 同机 app 访问 loopback / chrome://inspect release |
| 构建 | clean clone assembleRelease / x86_64 / 无 keystore / 无 bootstrap |

### 8.2 验收标准（建议）

- 首启只产生 1 个 init 协程、1 个 code-server、1 个 health job。
- 断网/杀进程后重新打开能继续安装，不需要清数据。
- Android 8/9/10 首启不崩溃；internal fallback 可用。
- `pkg install hello`（或某个小包）成功，`which hello` 可执行。
- `/usr/bin/env` shebang 可执行；`awk` 指向 Termux gawk。
- `git clone` 在推荐工作区可用；编译产物可执行（或 UI 明确提示用内部工作区）。
- 同网 PC 无法访问 8080；release 不能 chrome://inspect。
- 键盘弹出时终端输入框可见。
- Release APK 与 HEAD 一致；clean clone 可构建；x86_64 首启成功。
- APK 体积目标：单 ABI ≤ ~35MB；双 ABI 可接受 ~67MB 但需说明。

---

## 9. 附录：证据与命令摘要

### 9.1 Bootstrap 资产

- `bootstrap-aarch64.zip`：3,764 entries，30.7MiB；包含 `SYMLINKS.txt`（1,161 条）、`bin/bash`、`bin/apt`、`bin/dpkg`、`lib/`。
- `bootstrap-x86_64.zip`：30.6MiB；build 任务不会自动下载。
- **16KB 正面发现**：338 个 ELF 全部满足 `PT_LOAD p_align >= 16384` 且 `p_vaddr % 16384 == p_offset % 16384`；对 Android 15/16 的 16KB page size 设备友好（已用 llvm-readelf 抽查 bash/apt/dpkg/libcrypto，Termux 子代理逐 ELF 校验）。
- bootstrap 顶层没有 `home/`；`extractBootstrap` 的 home 分支不会触发，app 也不生成 `.bashrc`。
- bootstrap 自带 `var/lib/dpkg/status` 记录 82 个包，`.list` 路径是旧 `com.termux`。

### 9.2 SYMLINKS 统计

- 总链接 1,161：`bin` 136、`lib` 50、`share` 941（大多是 man/doc）、`include` 23、`etc` 8。
- `bin/` 手工 applet 列表覆盖约 39 个，剩余约 97 个（`ls/pwd/id/whoami/uname/date/awk/which/[ /mktemp/sha256sum` 等）。Android toybox 可能兜底一部分，但不是全部/行为不同。
- 顶层 `lib/*.so*` 链接 43；正则只覆盖 `.so.X.Y*`，`libcrypto.so`、`libssl.so`、`libevent*.so`、`libtinfo.so`、`libcurses.so`/`libtermcap.so`、`libtermux-exec.so` 等缺失；不同计数口径下约 29–37 个。
- `include/` 链接 23（头文件别名）；`etc/apt/trusted.gpg.d` 8 个 key 链接缺失。
- `SYMLINKS.txt` 缺失时 Termux 官方安装器直接抛异常；本项目完全不读。

### 9.3 Termux `.deb` 路径验证

```text
$ python (extract hello_2.12.3-1_aarch64.deb data.tar.xz)
./data/data/com.termux/files/usr/bin/hello
./data/data/com.termux/files/usr/share/doc/hello/copyright
...
```

TUR `code-server_4.143.1_aarch64.deb`：

- `Depends: libandroid-spawn, libsecret, krb5, nodejs-24, ripgrep`
- `lib/code-server/lib/node -> /data/data/com.termux/files/usr/opt/nodejs-24/bin/node`（绝对 symlink）
- `data.tar` 首条路径 `./data/data/com.termux/files/usr/...`
- 无 postinst（control 中只有 control）；但其他依赖包可能有脚本。

### 9.4 APK 事实

| 项 | Release |
|---|---|
| size | 67,590,569 B（64.46 MiB） |
| assets | aarch64 32,203,582 B STORED；x86_64 32,112,380 B STORED；合计 95.2% |
| classes.dex | 692,643 B compressed |
| `res/it.png`（launcher） | 1,692,371 B（2.5%） |
| targetSdk | 28 |
| permissions | INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, WAKE_LOCK, POST_NOTIFICATIONS, READ/WRITE_EXTERNAL_STORAGE, MANAGE_EXTERNAL_STORAGE |
| signing | v2 only，CN=AMCode；本地 JKS 匹配 |
| WebView debugging | release 仍开启 |

### 9.5 构建/lint

- `assembleDebug` ✅ 2m52s；`lintDebug` ✅ 0 errors / 22 warnings；`assembleRelease` ✅ 47s。
- clean clone `validateSigningRelease` ❌ 缺 `amcode-release.jks`。
- 警告：`aaptOptions` deprecated、`URL(String)` deprecated、`SetWorldReadable`、6 unused resources、SdCardPath ×5、IconLocation、MonochromeLauncherIcon、HardcodedText/SetTextI18n、ObsoleteSdkInt ×4。

### 9.6 关键复现/证据命令

```bash
# 1) bootstrap 内容与 symlink 差异
python - <<'PY'
import zipfile, os
z=zipfile.ZipFile('app/src/main/assets/bootstrap-aarch64.zip')
print(len(z.namelist()), [n for n in z.namelist() if 'SYMLINKS' in n])
PY

# 2) .deb data 路径
# python: 解 ar + data.tar.xz，首条 .data 路径应为 ./data/data/com.termux/...

# 3) dpkg DB
grep -m1 '/data/data/com.termux' app/src/main/assets/bootstrap-aarch64.zip  # 解压后看 var/lib/dpkg/info/*.list

# 4) 镜像 pipeline 退出码
curl -sI https://invalid.invalid/ | head -1; echo status=$?   # -> 0

# 5) APK 资产
unzip -l app/build/outputs/apk/release/app-release.apk | grep bootstrap

# 6) 构建/签名
./gradlew assembleDebug lintDebug assembleRelease
git archive HEAD | tar -x -C /tmp/amcode-clean && cd /tmp/amcode-clean && ./gradlew :app:validateSigningRelease
```

### 9.7 四个子代理的覆盖

- **Android 层**：启动/权限/Service/WebView/进程/ANR/错误恢复/构建；对应本报告 P0-1～P0-4、P1-3～P1-5、P1-8～P1-11。
- **Termux/apt 层**：SYMLINKS、termux-exec、.deb prefix、dpkg DB、pkg/keyring、mirror、ABI、patch 覆盖；对应 P0-5、P1-1/P1-2、P1-6/P1-12。
- **上游对比**：code-server 官方 Termux/FAQ/安全指南、platform.ts、Open VSX、CodeFA/VHEditor/Termux、Play/16KB；对应 P1-7、第 6 节。
- **构建层**：APK/签名/资产/lint/R8/未使用依赖/clean clone；对应 P1-6、第 9.4/9.5 节。

---

## 9.8 模拟器实测（2026-10-24，API 34 x86_64 Pixel 6）

按本报告的建议搭了本机 AVD：`AMCode_Pixel6_API34`（Pixel 6、x86_64、API 34、4GB RAM、8GB data、WHPX 加速、带窗口）。用**原版 debug APK** 实测，直接复现了两个 P0：

1. **首启双初始化**：logcat 同一进程出现两次 `=== VSBoxed Environment Setup ===`（线程 2653/2655），两次 bootstrap 解压/两次 patch；apt 报：
   ```
   E: Could not get lock /data/user/0/com.devbox/files/usr/var/cache/apt/archives/lock.
      It is held by process 3080 (apt)
   [retry] Download interrupted, retrying in 3s...
   ...
   [FAILED] Could not install packages after 3 attempts.
   ```
   截图见 [`review-assets/first-run-setup.png`](review-assets/first-run-setup.png)、[`review-assets/first-run-failed.png`](review-assets/first-run-failed.png)。
2. **失败后无法恢复**：`am force-stop` 后重新打开，未再尝试安装，直接显示：
   ```
   An error occurred
   Failed: code-server not installed. Run: pkg install code-server
   ```
   截图见 [`review-assets/relaunch-brick.png`](review-assets/relaunch-brick.png)。这验证了 P0-4 的 `isReady()` 死路，也说明“清应用数据/重装”是当前唯一恢复方式。

为了继续验证模拟器本身能跑到 VS Code，我在**独立副本** `vsboxed-emulator-test` 里只加了三个最小修复（未改原仓库）：

- `MainActivity`：`startupStarted` 防重复启动；
- `CodeServerService`：`initJob` single-flight；
- `TermuxManager`：`.amcode-ready` 完成标记 + `isReady()` 要求 code-server + `tryInstall` 用 `isBootstrapReady()`。

验证结果：

- 修复版首启只有一个初始化协程，apt 不再抢锁；139MB 包下载完成后，`dpkg --root` + 搬运 + patch 走通，`files/.amcode-ready` 生成，`bin/code-server` 存在。
- code-server 启动，WebView 成功渲染 VS Code 界面（Explorer、Search、Chat、Restricted Mode 都在），截图见 [`review-assets/fixed-vscode.png`](review-assets/fixed-vscode.png)。
- 这说明 **P0-2/P0-4 的最小修复方向是有效的**，也说明模拟器本身完全可用。
- 但同一份运行日志还暴露了新的运行时问题：
  - `@vscode/ripgrep-universal/bin/android-x64/rg ENOENT`：TUR 包里的绝对 symlink 仍指向 `/data/data/com.termux/...`，P1-2 的“绝对 symlink 未 patch”被实测命中；
  - `spdlog.node` `dlopen failed: cannot locate symbol _ZNSt6__ndk113__hash_memoryEPKvm`：native 模块 libc++ ABI 不匹配，extension host 功能会残缺；x86_64 模拟器上尤其明显，需在 arm64 真机复核。
- 在同一个测试环境里执行 `apt-get -y install hello`：下载成功，但 `dpkg` 报 `error creating directory './data/data/com.termux': Permission denied`，**P0-5 从静态推断升级为实测**。
- `#!/usr/bin/env sh` 脚本执行报 `No such file or directory`；`awk` 实际落到 `/system/bin/awk`（toybox），**P1-2 的 shebang/工具链问题也被实测**。
- 用户提供的 `pkg install clang` 截图进一步确认了 GPG 层：`pkg` 把源切到 `mirror.nyist.edu.cn` 后，主仓和 TUR 都报 `NO_PUBKEY` / `repository is not signed`。截图见 [`review-assets/pkg-install-clang-nopubkey.png`](review-assets/pkg-install-clang-nopubkey.png)。这不是单独一个源的问题，而是 `pkg` 重写 sources.list + keyring 未安装的组合；修 keyring 后还要继续修 dpkg prefix，见 P0-5。

测试副本只用于验证模拟器/修复方向，不是正式补丁；它额外加了一个仅在模拟器可用的 `10.0.2.2:7897` HTTP 代理（本机 TUR 直连约 28KB/s，代理约 4.6MB/s），正式项目不要带这个硬编码。正式修复还需处理存储选择顺序、重试、超时、`pkg install` 和 native 模块/ripgrep 链接。

### 9.9 模拟器环境

- **主用 AVD（平板）**：`AMCode_Tablet_API34`，Pixel Tablet、x86_64、API 34、**1600×1200、4:3 横屏、240dpi**、4GB RAM、8GB data、GPU/硬件键盘开启。
  ```powershell
  D:\Android\Sdk\emulator\emulator.exe -avd AMCode_Tablet_API34 -gpu auto -no-audio -port 5558
  ```
  截图：[`review-assets/tablet-home.png`](review-assets/tablet-home.png)。adb serial：`emulator-5558`。
- **保留的手机 AVD**：`AMCode_Pixel6_API34`（Pixel 6、1080×2400、port 5556），用于复现原版首启问题；截图见 9.8。
- **平板验证**：4:3 横屏平板已经成功进入 code-server 的完整 VS Code 界面；最终 APK 已覆盖安装，单次初始化、动态端口 `127.0.0.1:40457`、persisted external HOME 都正常。截图：[`review-assets/final-tablet4.png`](review-assets/final-tablet4.png)。修复记录见 [AMCODE_FIXES_2026-10-24.md](AMCODE_FIXES_2026-10-24.md)。
- 仓库根目录的 [`run-emulator.bat`](run-emulator.bat) 现在默认启动平板 5558；桌面快捷方式 `AMCode Android Emulator` 指向它。
- 平板上已经预装“最小修复 + 模拟器代理”的测试版，正在跑首次环境安装；最终 A/B 修复构建完成后会覆盖安装，验证 `pkg install` 和 VS Code。
- 本机未安装 Android Studio；独立 emulator + AVD 已经能提供桌面可视化调试，效果与 Android Studio Device Manager 相同。若以后装 Android Studio，它会直接识别这两个 AVD。

---

## 10. 最后建议

如果这是你的个人项目/技术演示：**先修 P0-1～P0-5，再把 P1-1～P1-4 做掉**，这个 app 就能从“经常翻车”变成“可日常使用”。native 路线值得保留。

如果这是要发布给别人的产品：**不要按现在的 README/Release notes 宣传**。要么收窄为“预装 VS Code + 基础工具链的 Android 编辑器”，明确不支持完整 pkg/扩展；要么转 CodeFA/VHEditor 路线，接受体积/性能代价，换取真正的 Linux/扩展兼容性。以 `targetSdk=28` + `MANAGE_EXTERNAL_STORAGE`，Google Play 也基本走不通，建议 GitHub/F-Droid 分发并做好安全默认值。

最值得先做的一件事：**把 `--auth none` + `0.0.0.0` 改掉**。这是唯一一个“别人现在就能利用”的问题；其他问题更多是用户自己踩坑。

---

*报告完。审计基线为 2026-10-24 的 `2d9b486`；同日的修复改动见 [AMCODE_FIXES_2026-10-24.md](AMCODE_FIXES_2026-10-24.md)，源码已修改、未 commit。*
