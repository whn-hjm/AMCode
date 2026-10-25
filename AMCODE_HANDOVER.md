# AMCode 交接与运维开发文档

> **本文档双重用途：① 项目交接；② 日常运维与开发手册。每次改动项目后必须更新本文档（尤其是 §11 变更记录与 §6/§7）。**
>
> 最近更新：2026-10-25 上午（Asia/Shanghai）
> 代码基线：`c229683`（本地 `main`，领先 origin 9 个 commit；v1.1.0 / versionCode 2）
> 工作区：`D:\Code\code-server\vsboxed`
> 远程：`https://github.com/whn-hjm/AMCode.git`
> 适用对象：zcode / 后续维护者

---

## 0. 给接手模型的提示词（简短版）

```text
你是 AMCode 的接手开发者。工作区：D:\Code\code-server\vsboxed，代码基线 c229683（v1.1.0）。
本地 main 领先 origin/main 多个 commit（含 2026-10-24/25 的六批修复），push 前不需要再处理 keystore（签名已走 gitignored keystore.properties，jks 从未入库）。

先完整阅读 AMCODE_HANDOVER.md（本文件——**项目唯一权威文档**：交接+运维+任务板+变更记录）。docs/history/ 是只读存档，不要更新；本轮评审结论已并入本文件 §10。
不要重做已修项（pkg/keyring/internal HOME/auth/并发修复等）；只从 §7 任务池按优先级做增量。

环境约束：
- 平板 emulator-5558（API34，1600×1200）是主测试机：不要清它的 app 数据。
- API28 headless 5560（C 盘 AVD）、API29 5570 / API26 5572（D:\avds，ANDROID_AVD_HOME=D:/avds 启动）用于 fresh/回归；5556 手机 AVD 不要启动。
- C 盘只剩 ~11G：新 AVD/镜像一律放 D 盘（SDK 本身在 D:\Android\Sdk）。
- Gradle 会清理 build/outputs 额外文件，成品 APK 构建后立即复制到 D:\。
- 改代码前 git status；每次构建后更新本文件 §11；不要提交 review-assets/。

v1.1.0 已发布：https://github.com/whn-hjm/AMCode/releases/tag/v1.1.0（online 通用 + offline arm64，附 SHA-256）。下一优先级（§7）：P2 剩余项 → P3 → §13 重任务池。

产品口径（2026-10-25 用户拍板）：**主线不做 proot**——proot 性能不可接受，native bionic 路线是项目立身之本。glibc 兼容在主线上只能走自编译/patched code-server（§13 P1-2）；proot 只允许作为未来一个独立的 proot 版本/分支存在，不进主线 backlog。
```

---

## 1. 项目是什么

AMCode（包名 `com.devbox`，仓库目录名 `vsboxed`）把 **Termux 原生用户态 + code-server + Android WebView** 打成一个 APK。打开 App 即进入 VS Code，不需要 Termux App、不需要 root、不需要 proot。

- 包名必须正好 10 字符：`com.devbox` 与 `com.termux` 等长，bootstrap 二进制/脚本里的路径按同长度替换。
- `minSdk=26`，`targetSdk=28`（刻意保留，targetSdk 29+ 会触发 W^X，不能从 app data 执行二进制）。
- `compileSdk=34`，AGP 8.4.0，Gradle 8.7（wrapper 带 distributionSha256Sum），JDK 21。
- code-server **4.143.1**（TUR），Node **24.18.0**，bootstrap 来自 Termux `bootstrap-2026.08.02-r1+apt.android-7`（双 ABI，构建期下载并 SHA-256 校验）。
- 版本：`versionName=1.1.0`，`versionCode=2`。
- 仅支持 64 位（arm64-v8a 真机 / x86_64 模拟器）；32 位 ARM 会拿到无法执行的 aarch64 bootstrap（§6.7）。

---

## 2. 当前架构

```text
MainActivity
  ├─ 无 WebView 设备探测（AOSP 镜像缺 WebView → 明确报错对话框）
  ├─ HomePrefs（internal 默认 / external 迁移 / 双向切换 / ~/storage/shared）
  ├─ 存储权限（API30+ MANAGE / API26-29 READ+WRITE，仅按需请求）
  ├─ CodeServerService（前台服务，初始化/健康检查/stop/重启）
  ├─ 文件选择器（onShowFileChooser → SAF，供扩展上传/VS Code webview）
  └─ WebView
        ├─ http://127.0.0.1:<动态端口>/?folder=<HOME>
        ├─ CookieManager 注入 HttpOnly code-server-session
        ├─ focus/IME inset/JS focus shim
        ├─ 内部 HOME 首次加载 → 一次性 Trust 提示（不自动信任）
        └─ URL 白名单：本机 host:port + about: + http/https/mailto/tel

CodeServerService（单飞 initJob + 管线所有权检查）
  ├─ ACTION_RESTART：取消旧管线（join ≤15s）→ 新管线
  ├─ 失败清理只由"当前拥有者"执行（被取代的管线失败不再拆服务）
  └─ 健康检查 10s / 最多重启 10 次（进度通知节流 2/s）

CodeServerLauncher
  ├─ TermuxManager.initialize()（Mutex 串行化）
  │    ├─ bootstrap 原子拷出（tmp+长度校验，失败自删，成功后删缓存）
  │    ├─ ensurePkgSetup()：marker 快路径 / full repair（含 dpkg --configure -a 自愈）
  │    ├─ offline bundle：staged deb 失败即清理；archives 按包名去重取最新
  │    └─ 在线 apt（镜像测速要求 HTTP 200）或 8 连接 Range 下载
  └─ code-server 进程
       ├─ 127.0.0.1 + ServerSocket(0) 动态端口（分配失败即失败，不回退 8080）
       ├─ 随机 token → SHA-256 → cache/run/code-server.yaml（0600，唯一 tmp 名）
       ├─ --auth password --config ...；POST /login + /mint-key 验证
       ├─ 取消/失败只 abort 本管线进程（不全局 stop 误杀新管线）
       ├─ 认证后再 checkpoint 才发布 RUNNING/会话
       ├─ PID 文件（pid/starttime/port）+ cmdline 特征校验的孤儿清理
       ├─ ripgrep symlink 修复 + android-as-linux execArgv 注入
       └─ 启动前种子 files.watcherExclude（不覆盖用户 settings）
```

### 关键文件

| 文件 | 作用 |
|---|---|
| `App.kt` | `HomePrefs`：HOME 模式、迁移、`~/storage/shared` 链接；崩溃日志 |
| `TermuxManager.kt` | bootstrap、pkg/apt/dpkg、keyring、SYMLINKS、offline bundle、marker 快路径、Range 下载 |
| `CodeServerService.kt` | 前台服务、单飞 initJob、管线所有权、健康检查、通知 |
| `CodeServerLauncher.kt` | code-server 进程、auth、PID/孤儿清理、ripgrep/shim、用户设置种子 |
| `MainActivity.kt` | 启动状态机、存储/通知权限、WebView、auth cookie、focus/IME、Trust/Storage UI、文件选择器 |
| `WebViewInterface.kt` | 已不再注册 JS bridge；保留但不要重新暴露 |
| `AndroidManifest.xml` | `requestLegacyExternalStorage`、`stopWithTask`、backup/dataExtraction 规则 |
| `assets/amcode-dpkg-wrapper.sh` | apt 调 dpkg 时改写 `.deb` 的 com.termux/com.devbox 路径 |
| `assets/amcode-pkg-setup.sh` | keyring/SYMLINKS/pkg/dpkg DB 修复 + wrapper tmp 残留清理 |
| `assets/tur.gpg` | TUR 公钥 |
| `assets/android-as-linux.js` | platform shim，execArgv 注入 |
| `app/keystore.properties` | **gitignored** release 签名（缺失→unsigned；残缺→构建失败） |
| `.github/workflows/android.yml` | CI：debug + lint + unsigned release |

---

## 3. 项目时间线

### 3.1 原始开发（2026-08）

| 时间 | 事件 |
|---|---|
| 2026-08-06 | 项目起步：Termux + bionic + code-server + WebView 架构 |
| 2026-08-07/08 | v1.0 发布、release 签名、seccomp/GPG 过滤、镜像测速、README/ARCHITECTURE、NODE_OPTIONS/LD_PRELOAD 实验及回滚 |

### 3.2 审计与三轮修复（2026-10-24）

| 时间 | 事件 |
|---|---|
| 白天 | 全量审计（4 子代理）→ `docs/history/AMCODE_REVIEW_2026-10-24.md` |
| 21:57 | `6f44555` 第一轮：pkg/keyring/dpkg wrapper/启动单次化/安全/动态端口 |
| 22:34 | `a9971e9` 第二轮：focus/IME、ripgrep、shim、libc++30、offline bundle |
| 23:44 | `ef2b56c` 第三轮：internal HOME、auth token、PID 清理、快路径、offline dpkg |
| 23:50 | `eb52968`/`fb2b154` 交接文档 + 路线图 |

### 3.3 深夜评审 + 六批修复（2026-10-24 深夜 ~ 10-25 上午）

| 时间 | commit | 内容 |
|---|---|---|
| 10-25 00:2x | `dc572f9` | 批次1-3：v1.1.0、Trust 提示、双向切换 UI、watcherExclude、keystore 外置、安装管线加固（unpack 硬门+半配置自愈、bootstrap 原子化、镜像测速 HTTP 200、offline 清理/去重、opt/share/include 改写、libc++ marker）、双 ABI bootstrap 下载+SHA256、dataExtractionRules |
| 10-25 02:0x | `5cd3ded` | 批次4-5：文件选择器、about:blank、三方 cookie 关、bindService 配对、configChanges 补齐、applyExternalHome 顺序、Restart join 超时、wrapper sha、依赖清理、R8 收紧（release -3.9MB）、死文件删除 |
| 10-25 02:4x | `c229683` | 批次6+docs：管线并发正确性（所有权检查/abort 本进程/auth 后 checkpoint/Mutex 串行/唯一 tmp/pid 校验/通知节流/脱敏）、无 WebView 设备处理、README/ARCHITECTURE 重写、CI |

**评审方式**：5 个评审子代理（Termux 层 / Android UI 层 / 启动器与服务层 / 构建发布层 / 文档一致性）+ 主 agent 全量读码；发现汇总见 §10。

---

## 4. 当前已验证行为（基线 c229683）

### 4.1 全 API 级别回归（2026-10-25，offline x86_64 debug v1.1.0）

| 设备 | 场景 | 结果 |
|---|---|---|
| 5558 平板 API34 | 覆盖安装（数据保留） | full repair 一次（asset 变化触发）→ marker 重写 → 二次启动快路径 102ms → 页面加载 ✓ |
| 5560 API28 release | **fresh 安装 offline release** | 30s 到页面；dpkg exit=0 clean；auth 302/401/200 ✓；非 loopback 拒绝 ✓ |
| 5560 API28 release | R8 收紧后 release 覆盖 | 快路径 33ms + 页面加载 ✓（无反射断裂） |
| 5570 API29 | fresh + 权限流 | fresh 30s ✓；切 external 拒绝路径（返回键）→ 保持 internal ✓；pm grant 后切换 → external HOME 生效 ✓；切回 ✓ |
| 5572 API26 (google_apis) | fresh + 权限流 | fresh ~60s ✓（AOSP 镜像无 WebView，见 §6.12）；grant 后双向切换 ✓ |

### 4.2 安装与 pkg

- offline bundle：`[dpkg] exit=0 (unpack=0 configure=0 audit=clean; 19 packages)`，不访问 TUR，仅主镜像 ~18MB 依赖。
- 镜像测速：三镜像取 HTTP 200 中最快（实测 USTC 193ms）。
- marker：full repair 8-12.5s（首装后）／快路径 32-130ms。
- dpkg wrapper 改写 `.deb` 前缀；`pkg install` 正常（debug 构建实测 hello/tree/clang；release 上 pkg 因 run-as/su 限制未直测，运行时管线与 debug 相同）。
- 半配置状态（`Status: install ok unpacked`/half-*）会被启动健康检查发现并由 `dpkg --configure -a` 自愈。

### 4.3 启动 / 安全

- 启动链：internal HOME → 动态端口 → auth(password+SHA-256) → cookie 注入 → `?folder=` 页面加载。
- 未认证：`GET /` → 302 login；`POST /mint-key` → 401；`/healthz` → 200；非 loopback 地址拒绝连接。
- token/SHA-256/cookie 三重 stdout 脱敏；PID 文件 + cmdline+starttime 校验清理；SIGKILL 前重验 cmdline。
- release：WebView 调试按 FLAG_DEBUGGABLE 关闭（在 setContentView 之前设置）。

### 4.4 存储与 Trust

- 新装默认 internal HOME，首启零权限弹窗；`~/storage/shared` 链接自动创建。
- Storage 通知 action：internal ↔ `/sdcard/AMCode` 双向切换（带 noexec 警告），文件永不移动；API26/29 拒绝/授权路径实测。
- internal 首次页面加载弹一次性 Trust 提示（标记在对话框实际显示后才写入）。

---

## 5. 构建与运行（运维手册·上）

### 5.1 环境

- Windows + JDK 21；Android SDK：`D:\Android\Sdk`；仓库自带 `gradlew.bat`。
- **release 签名**：读 `app/keystore.properties`（gitignored，本机已存在）或 `AMCODE_STORE_FILE/AMCODE_STORE_PASSWORD/AMCODE_KEY_ALIAS/AMCODE_KEY_PASSWORD` 环境变量。两者皆无 → unsigned release；keystore.properties 残缺 → 构建报错（防呆）。
- bootstrap 双 ABI zip 构建期自动下载（github/ghproxy/gh-proxy 三源）+ SHA-256 校验，缺失即构建失败。

### 5.2 构建命令

```bash
# 默认在线 debug（通用：双 ABI bootstrap）
./gradlew.bat clean :app:assembleDebug

# 默认在线 release（本机自动签名；通用 64MB）
./gradlew.bat :app:assembleRelease

# x86_64 offline debug（模拟器回归用；单架构）
./gradlew.bat :app:assembleDebug -PofflineBundle=true -PofflineBundleArch=x86_64 \
  -PofflineBundleSourceDir=D:/amcode-debs -PbootstrapArch=x86_64

# arm64 offline release（发布用，单架构 161.8MB）
./gradlew.bat :app:assembleRelease -PofflineBundle=true -PofflineBundleArch=aarch64 \
  -PofflineBundleSourceDir=D:/amcode-debs-arm64 -PbootstrapArch=aarch64
```

**bootstrap 资产机制（2026-10-25 起）**：zip 缓存在仓库根 `bootstrap-cache/`（gitignored），构建时按 `-PbootstrapArch`（空=双 ABI）staged 到 generated assets；缺省下载（github/ghproxy/gh-proxy 三源）+ SHA-256 固定校验，缺失构建失败。**不要再往 `app/src/main/assets` 放 zip。**

**构建坑**（不变）：
- offline ↔ 默认构建切换建议 `clean`，并检查 APK 内 offline-debs 有无；成品立即复制到 `D:\`。
- 离线 deb 源：`D:\amcode-debs`（x86_64）、`D:\amcode-debs-arm64`（aarch64）。
- CI（GitHub Actions，`.github/workflows/android.yml`）：debug+lint+unsigned release，bootstrap 缓存。

### 5.3 AVD 清单

| AVD | 端口 | 用途 | 位置 |
|---|---|---|---|
| `AMCode_Tablet_API34` | 5558 | 主测试平板（**勿清数据**） | C 盘默认 |
| `AMCode_Tablet_API28` | 5560 | API28 headless 回归 | C 盘默认 |
| `AMCode_API29` | 5570 | API29 回归 | `D:\avds` |
| `AMCode_API26` | 5572 | API26 回归（**必须 google_apis**，default 镜像无 WebView） | `D:\avds` |
| `AMCode_Pixel6_API34` | 5556 | **不要启动** | C 盘 |

5570/5572 启动方式：`ANDROID_AVD_HOME=D:/avds emulator -avd AMCode_API29 -port 5570 -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect`

### 5.4 常用验证命令

```bash
ADB="D:/Android/Sdk/platform-tools/adb.exe"
$ADB -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5558 logcat -s TermuxManager:I CodeServerLauncher:I CodeServerService:I MainActivity:I HomePrefs:I
# 快路径检查
$ADB -s emulator-5558 logcat -d -s TermuxManager:I | grep -E 'ensurePkgSetup (fast path|full repair)'
# auth（debug 构建 run-as 可用；release 用 logcat 里的端口）
$ADB -s emulator-5558 shell run-as com.devbox cat files/run/code-server.pid
$ADB -s emulator-5558 forward tcp:<PORT> tcp:<PORT>
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://127.0.0.1:<PORT>/   # 302 login
# 触发存储对话框：am start -n com.devbox/.MainActivity -a com.devbox.action.OPEN_HOME_SETTINGS
```

---

## 6. 已知问题与边界（接手先看；已修项已移除，见 §10/§11）

1. **单 APK 内嵌双 ABI bootstrap（~61MB / 占 APK 95%）**：assets 不受 abi split 影响，arm64 真机也带着 x86_64 zip。per-ABI flavor/-P 参数可把单架构 APK 降到 ~33MB。这是发布前最值得做的减包项。
2. **pkg-setup 修复脚本不持 dpkg lock**：full repair 的 sed 改写与"上次会话残留终端里正在跑的 apt/dpkg"存在竞态窗口（marker 失效 + 恰逢终端 apt 时触发；后果是 DB 改写丢失，下次启动自愈）。
3. **Range 下载跨运行续传的段布局漂移**：part 文件按 index 命名，两次启动间仓库版本变化时旧 part 可能被误当作新布局前缀；apt 提供 SHA-256 时兜底（损失效率），无 hash 的源可能装坏 deb（当前 TUR/主仓都提供 hash）。
4. **健康检查重启路径未完全走 initJob 单飞通道**：重启循环直接调 prepareAndStart（现有 generation/abort 防护已消除跨管线误杀，但两条路径理论上仍可并存短暂时间）。
5. **API28 `py3compile Bad system call`**：seccomp 下 python postinst 部分失败；dpkg exit=0/audit clean、code-server 正常，configure -a 自愈机制覆盖；Python 字节码可能不完整。
6. **旧安装的 code-server/node 未补登记 dpkg DB**（早于 marker 机制的安装）；fresh/offline 已正确登记。
7. **32 位 ARM 不支持**：`bootstrapAsset()` 对 armeabi-v7a 落到 aarch64 zip，装得上跑不起来且报错含糊。应显式 fail-fast 或文档声明。
8. **多用户/work profile 路径视图**：dpkg DB 与 shell 脚本用 `/data/data/com.devbox` 字面路径，secondary user（`/data/user/10/...`）下 DB 记录与实际路径不一致（可运行，`dpkg -V` 错位）。声明不支持即可。
9. **i18n 残余**：MainActivity 对话框/Toast 已全量迁移 values-zh（021cda3）；CodeServerService 的通知文案与 strings.xml 里 initializing/error_occurred 等旧键仍仅英文（中文回落英文）。
10. ~~**mint-key 探测的潜在副作用**~~ **已核实无问题（2026-10-25，主 agent 查 v4.143.1 源码）**：`POST /mint-key`（src/node/routes/vscode.ts:213，ensureAuthenticated 保护）只是惰性创建/读取 user-data-dir 下一个 32 字节 `serve-web-key-half` 文件（模块级缓存，缺失才写一次，跨启动复用）——不是会累积的 API key，保留现用法。`GET /healthz` 确认免认证（routes/index.ts:156 独立挂载）。
11. **glibc native 扩展不可用**（bionic 限制）：纯 JS/语言包可用。主线兼容路径是自编译/patched code-server（§13 P1-2）；**主线不做 proot**（用户拍板：性能差）——proot 只可能以独立版本形态出现在未来。
12. **AOSP 无 WebView 的设备**：现已给出明确报错对话框（未在真机复测；API26 AOSP 镜像实测触发该路径）。极旧 WebView 版本无版本门槛提示（P3 池）。
13. **默认在线 TUR 仍慢**：TUR 无镜像，8 连接受 GitHub 限速；offline 版是解决方案（已实现）。
14. **auth 使用 SHA-256 hashed-password** 是 code-server 4.143.1 legacy 路径；升级 code-server 必须重新验证。
15. **keystore 历史**：`amcode-release.jks` 从未入库；旧密码 `amcode123` 曾随 6d3a6d1 进入 origin 历史（无 jks 文件本身，密码无用）。公开分发前轮换 keystore 仍是廉价保险（轮换=无法覆盖升级，需用户决策）。

---

## 7. 任务池（按优先级；做完一项更新一项；新发现随时追加）

> 本节是**活任务板**：完成打勾并写 commit 号；子代理/后续维护者从这里领任务。文件独占约定见括号。
> 重任务候选池（自编译 code-server/16KB/离线 SDK 等 XL 级）的完整展开见附录 §13（原 docs/history/AMCODE_ROADMAP.md 的活跃摘要）。**口径：主线不做 proot**（性能不可接受）；proot 仅为未来独立版本候选。

### P1（发布前）

- [x] **单 ABI bootstrap 减包**：`-PbootstrapArch=<abi>`（缓存移至 `bootstrap-cache/`，staged 到 generated assets；默认仍双 ABI 通用包）。产物：online 通用 64MB；offline arm64 单架构 **161.8MB（原 194.7MB）**。（2026-10-25 主 agent）
- [x] **push + GitHub Release**：v1.1.0 已发布 https://github.com/whn-hjm/AMCode/releases/tag/v1.1.0 （online 63.8MB 通用 + offline arm64 154.3MB，SHA-256 见 release notes；CI 绿）。
- [x] **amcode-dpkg-wrapper 与 pkg-setup 的 dpkg lock**：repair 前 bounded 等待残留 apt/dpkg/pkg 退出（`b4907fd`；5558 实测 full repair warnings=0）

### P2

- [x] **[T-B]** Range 下载 part 文件按区间起点命名（.part.<start>-<end>），消除断点续传布局漂移（`558efcf`；offline 路径不走 Range，编译+逻辑验证）
- [x] **[T-C]** 健康检查重启统一走 initJob 单飞通道：恢复循环搬进管线 recovery 分支 + pipelineLock 线性化；launcher 失败不再全局 stop、auth config 写入前查 generation（`3c89736`）
- [x] **[T-D1]** 旧安装 dpkg DB 补登记：status 缺条目时一次性写入 stanza+info list（`558efcf`；5558 模拟旧装实测：删条目→重启→自动补回 2 包）
- [x] **[T-D2]** 32 位 ARM fail-fast：bootstrapAsset 返回 null + 明确报错（`558efcf`）
- [x] **[T-G1]** resolv.conf 仅缺失时写默认，保留用户 DNS 定制（`558efcf`）
- [x] **[T-F]** mint-key 语义调研：v4.143.1 源码证实无副作用，保留现用法（见 §6.10）
- [x] **[T-G2]** D2D cache 域——CI lint 证伪：cacheDir 本就不参与备份，改为注释说明（`3c89736`）
- [x] **[T-G3]** terminal 输出持久化到 files/logs/setup.log（1MB 轮转）（`558efcf`）
- [x] **[T-E1]** i18n 迁移 values-zh：72 键全量迁移、isChinese() 删除、zh-CN per-app locale 实测（021cda3；CodeServerService 通知文案留英文，见 §6.9）
- [x] **[T-E2]** WebView 版本门槛：provider major < 70 弹非阻塞过旧警告（021cda3；模拟器 WebView 均 ≥70，逻辑评审验证）
- [ ] `~/storage/shared` 无 MANAGE 时的按需授权/拒绝/重试 UI 全回归（已部分覆盖）
- [ ] **[T-H]** dpkg wrapper 对大 deb 的 ~6 倍磁盘峰值：空间预检或流式评估（tmp 残留已清，见 §6.2 相邻风险）

### P3（体验/工程）

- [ ] UI/UX：extra-keys、virtual mouse（见 §13 P1-8）。

### 长期

- 自编译 Android-native code-server（glibc 兼容的主线路径）、16KB page、TUR 镜像/CDN——展开见 §13。proot 已移出主线（独立版本候选，见 §13 P1-1 备注）。

---

## 8. 维护约定（每次改动都读一遍）

1. 改代码前 `git status`；不要覆盖未跟踪的 review-assets/。
2. 每次改动后：
   1. 构建（必要时 clean）+ 5558 或专用 AVD 验证；
   2. **更新本文件 §3 时间线 + §4 验证状态 + §11 变更记录**；
   3. commit（不要提交 `review-assets/`）。
3. 不要启动手机 AVD（5556）；不要清 5558 数据；fresh 测试用 5560/5570/5572。
4. 不要恢复 `--auth none`；不要把 token/hash/cookie 写进 argv/env/logcat。
5. 不要用 `--root` 跑 offline dpkg（当前 `--instdir` + configure；改前读 TermuxManager 注释）。
6. 不要移除 android-as-linux 的 execArgv 注入；不要让 `terminal_output` 可聚焦。
7. 构建产物立即复制到 `D:\`。
8. **本文件是唯一权威**：与代码冲突时以代码+实测为准，然后更新本文件。

---

## 9. 运维手册（故障排查）

| 症状 | 原因 | 处置 |
|---|---|---|
| 首启反复 "Extraction failed" | bootstrap 缓存损坏（旧版本 bug，已修：原子拷贝+失败自删） | 新版本自愈；旧版本需 pm clear |
| 启动卡 "Installing packages" 很久 | 在线版 TUR 慢（GitHub 限速） | 等（断点续传）；或改装 offline APK |
| `dpkg --audit` 报 half-configured | postinst 曾失败（如 API28 py3compile） | 下次启动自动 `dpkg --configure -a` 自愈；手动：终端跑 `dpkg --configure -a` |
| 怀疑 pkg 层坏（NO_PUBKEY/dpkg 路径错） | wrapper/keyring/symlink 失常 | 删 `files/.amcode-pkg-setup` marker → 下次启动强制 full repair |
| 划掉任务卡后 VS Code 无法访问 | stopWithTask 停了服务（设计如此） | 重开 App（PID/孤儿自动清理） |
| code-server 端口冲突/旧进程残留 | 异常退出 | 启动前自动清理（PID+cmdline 校验）；确认 `files/run/code-server.pid` 与 `ps` |
| 打开即白屏+Toast 崩溃 | 设备无 WebView provider | 装系统 WebView（新版本有明确对话框提示） |
| 外部 HOME 下 git/编译失败 | /sdcard FUSE noexec | Storage action 切回 internal HOME |
| 通知栏没有进度更新 | 系统限流（已节流 2/s） | 正常；看 logcat |
| `run-as: package not debuggable` | release 构建 | 从 logcat `code-server PID=... bound to 127.0.0.1:<port>` 拿端口 |

**紧急恢复**：`pm clear com.devbox` 会清掉 internal HOME 的用户文件（含 `~/` 下项目）——清前先确认文件在 `/sdcard/AMCode` 或已备份。卸载重装同样清 data。

---

## 10. 评审发现汇总（2026-10-24/25，5 个评审子代理 + 主 agent 全量读码）

> 已修复项的明细在 §11 对应批次里；此处只保留结论与**未修复项**（与 §6/§7 一一对应）。

### 已全部修复（不再列）
- **Termux 层**：installDebFiles 吞错、bootstrap 非原子、镜像测速 head 假阳性、offline staged 残留/双版本、wrapper tmp 残留、opt/share/include 改写缺口、libc++ 每次全扫、缓存 zip 永存/错名、矛盾注释、死代码。
- **UI 层**：无文件选择器、onReceivedHttpError 缺失、三方 cookie、about:blank 吞导航、bindService 重复、configChanges 缺 uiMode、applyExternalHome 先设后验、Trust 标记时机、SWITCH_EXTERNAL 静默兜底、updateProgress 残留重试监听、settings 种子覆盖非对象值、backup 规则漏 ABI、崩溃日志 null。
- **Launcher/Service 层**：被取代管线 stopSelf 拆服务（P1）、CE-stop 全局误杀、auth 后无 checkpoint、initialize 非重入、auth-config/shim/settings 并发覆盖、健康循环 check-then-act、pid 校验空 start 旁路、SIGKILL 不复验、重启失败不更新通知、通知刷屏、hash/cookie 不脱敏、reader 不关、findFreePort 回退 8080；确认 R8 收紧不影响 getPid 反射（目标是平台类）。
- **构建层**：wrapper 无 sha、keystore 半填静默 unsigned、downloadBootstrap 永不 UP-TO-DATE、deb 未 noCompress、死文件（cpp/init.gradle.kts/bootstrap_setup.sh）、proguard 全量 keep、未用依赖、CI 缺失。
- **文档层**：README/ARCHITECTURE 全面过时（已重写）；根 ROADMAP 已标注存档；DEVELOPMENT.md 方案表两行过时（本地文件，未改）。

### 仍开放
见 §6 / §7。补充两个低置信度项：mint-key 副作用（§6.10）、D2D 迁移默认范围含 cache 域（§7-P3）。

---

## 11. 变更记录（每批次一条，倒序；新改动加在最上面）

### 2026-10-25 下午 · 产品口径：主线不做 proot
- 用户拍板：proot 性能不可接受，主线坚持 native bionic；glibc 兼容主线路径改为自编译/patched code-server（§13 P1-2）。
- §13 P1-1 降级为"未来独立 proot 版本"候选备忘；§0/§6.11/§7 同步更正。README/ARCHITECTURE 的 "no proot" 卖点描述不受影响。

### 2026-10-25 中午 · `021cda3` i18n + WebView 门槛 + **GitHub Release v1.1.0**
- MainActivity 72 个 string 键全量迁移 values/values-zh，isChinese() 删除；zh-CN per-app locale 实测（存储对话框参数化渲染正确）。
- WebView provider major<70 非阻塞过旧警告。
- **发布**：clean 重建双 APK（online 63.8MB 通用 / offline arm64 154.3MB 单架构）→ GitHub Release v1.1.0（含 SHA-256 与 highlights）。CI 全绿链路持续验证（compile+lint）。
- 残余记录：CodeServerService 通知文案英文（P3）；release 变体今日早些时候已做 fresh 回归（R8 验证），后续增量均逐项验证。

### 2026-10-25 上午（续）· `558efcf`+`3c89736` 任务板批次（T-A~T-H）
- **T-A** `-PbootstrapArch` 单架构打包（bootstrap-cache/，arm64 offline 194.7→161.8MB，5560 单架构 fresh 45s 全绿）。
- **T-C** 崩溃恢复并入 initJob 管线（recovery 分支+pipelineLock；健康循环只探测+交接）；launcher prepareAndStart 失败不再全局 stop；auth config 落盘前查 generation。
- **T-B** Range part 按字节区间命名 + 旧式/新式残留统一清理。
- **T-D1** 旧安装 dpkg DB 一次性补登记（模拟旧装实测通过）；**T-D2** 32 位 ABI 明确拒绝；**T-G1** resolv.conf 保留用户定制；**T-G3** setup.log 持久化（1MB 轮转）。
- **T-F** mint-key 源码核实：无副作用。**CI**：gradlew exec bit、去 setup-android、修 data-extraction cache 域 lint 错误。
- **T-H**（新发现入池）：wrapper 大 deb 磁盘峰值预检。

### 2026-10-25 上午 · `d78c59c` 文档整合 + CI 修复
- **唯一项目文档规则**：AMCODE_HANDOVER.md（+§13 重任务池摘要）；10-24 四份历史文档移入 docs/history/ 只读存档；根 ROADMAP.md 删除；README 补 `-PbootstrapArch`。
- CI 三连修：gradlew +x、移除 setup-android（runner 预装 SDK）、lint 抓到 data-extraction 规则非法 cache 域。

### 2026-10-25 上午 · `062c342` 单 ABI bootstrap 减包 + 任务板化
- bootstrap-cache/ + `-PbootstrapArch` staging；dataExtractionRules cache 注释；HANDOVER 重构为交接+运维+任务板+评审汇总+变更记录五合一。

### 2026-10-25 上午 · `c229683` 管线并发正确性 + minSdk 验证 + 文档
- **并发**：被取代管线失败不再 stopSelf（所有权检查）；取消的 start 只 abort 本进程；auth 后 checkpoint；ACTION_RESTART join 健康循环；startHealthChecks 同步化；TermuxManager.initialize 加 Mutex；auth-config/shim/settings 唯一 tmp 名；PID 匹配要求 starttime；SIGKILL 前复验 cmdline；findFreePort 失败即败；stdout 三重脱敏；通知节流 2/s；重启失败也更新通知。
- **minSdk**：无 WebView 设备给明确对话框（API26 AOSP 镜像实测发现）。
- **文档**：README/ARCHITECTURE 全面重写（v1.1.0 事实）；根 ROADMAP 存档标注；GitHub Actions CI。
- **验证**：API26(google_apis)/29 fresh + 双向切换 ✓；5558 覆盖安装快路径 ✓；5560 release fresh + R8 release ✓。

### 2026-10-25 凌晨2点 · `5cd3ded` WebView/Service 边界 + 构建瘦身
- 文件选择器（SAF）；about:blank 放行；主帧 HTTP 错误日志；三方 cookie 关；bindService/unbind 配对；configChanges 补 uiMode|density|fontScale|keyboard；applyExternalHome 先验证后设置；Trust 提示显示后才标记；进度行不再带重试监听；Restart join 15s 超时；settings 种子跳过非对象值。
- 构建：wrapper distributionSha256Sum；keystore 半填报错；deb noCompress；downloadBootstrap UP-TO-DATE；删 material/webkit/lifecycle-service/viewBinding/androidTest 依赖；删 cpp/init.gradle.kts/scripts；**R8 全量 keep 移除（release -3.9MB，实测无回归）**。
- 验证：5558 debug + 5560 release（R8）双端页面加载 ✓；5558 切 external→internal 双向 UI 实测 ✓。

### 2026-10-25 凌晨0-1点 · `dc572f9` v1.1.0 功能批 + 安装管线加固
- versionCode 2 / 1.1.0；keystore.properties/AMCODE_* 签名；internal Trust 一次性提示；Storage action 双向切换（noexec 警告）；watcherExclude 种子；WebView 调试决定移到 inflate 前；dataExtractionRules + backup 排除补全；崩溃日志 null 安全。
- 安装：installDebFiles 以 unpack 为硬门 + 半配置健康检查 + configure -a 自愈；bootstrap tmp+长度校验+失败自删+成功删缓存（按 ABI 命名）；镜像测速要求 HTTP 200；offline 失败清理 staged deb；archives 按包名去重；首装改写扩到 opt/share/include；libc++ 扫描结果 marker 化；pkg-setup 清 wrapper tmp 残留；死代码删除。
- 构建：双 ABI bootstrap 下载 + SHA-256 固定（aarch64=90a352db…、x86_64=c2d9892f…）；gh-proxy 第三源。
- 验证：5558 full repair 8.5s→快路径 103ms；5560 release fresh 30s 全绿；auth 302/401/200；Trust 对话框截图确认。

---

## 12. 文档地图与治理（2026-10-25 整合后：唯一项目文档 = 本文件）

**规则：项目内的计划/任务/评审/运维/变更记录只写进本文件；`docs/history/` 是只读存档，不再更新；README/ARCHITECTURE 面向用户与外部架构说明，随对外行为变化才改。**

| 文件 | 地位 |
|---|---|
| `AMCODE_HANDOVER.md`（本文件） | **唯一权威**：交接、运维手册、任务板（§7）、评审汇总（§10）、变更记录（§11）、重任务池摘要（§13） |
| `README.md` / `ARCHITECTURE.md` | 对外用户文档 / 架构说明（v1.1.0 现状） |
| `docs/history/AMCODE_REVIEW_2026-10-24.md` | 存档：10-24 全量审计（基线 2d9b486，多数已修） |
| `docs/history/AMCODE_FIXES_2026-10-24.md` | 存档：10-24 三轮修复详情 |
| `docs/history/AMCODE_NEXT_BATCH_PLAN.md` | 存档：10-24 批次计划（已执行完毕） |
| `docs/history/AMCODE_ROADMAP.md` | 存档：重任务池完整版（活跃摘要在本文件 §13） |
| `DEVELOPMENT.md`（gitignored 本地） | v1.0 过程记录，历史参考 |

---

## 13. 附录：重任务池摘要（原 AMCODE_ROADMAP.md 活跃部分；展开细节见 docs/history/）

评分：P0=发布前必须 / P1=高价值 / P2=体验工程 / P3=研究型；难度 S(<半天)/M(1-2天)/L(3-7天)/XL(1-3周+)。

### P1 重任务（按价值排序）

| ID | 任务 | 难度 | 一句话说明 | 验收 |
|---|---|---|---|---|
| P1-1 | Proot/glibc（**已移出主线**） | XL | **2026-10-25 用户拍板：主线不做 proot（性能不可接受，native bionic 是立项根基）**。保留条目仅作为"未来独立 proot 版本"的候选备忘：App 内可选跑 Debian rootfs + proot，与主线并存为独立分支/变体，不并入主线 backlog | 若未来立项独立版：装上并运行一个 glibc native 扩展，且与主线 APK 明确区隔 |
| P1-2 | **自编译 Android-native code-server** | XL | 用 Termux clang/NDK 编译 spdlog/watcher/node-pty/ripgrep，摆脱 TUR 包与 libc++ ABI 问题 | 扩展宿主无 ABI 错误；包体积 < TUR 的 120MB |
| P1-3 | **16KB page-size 重建** | L/XL | Android 15/16 的 16KB 设备硬门槛；重链 `-Wl,-z,max-page-size=16384` | 16KB AVD 可启动、pkg 正常 |
| P1-4 | **离线 SDK/自定义 bootstrap** | L/XL | 把 git/clang/python 等预打进可选 asset pack，首装 <1-2 分钟零网络 | fresh 安装 <2 分钟 |
| P1-5 | TUR 镜像/CDN + 断点差分 | L | 自建 GitHub Releases/R2 缓存固定版本 deb | 无代理下载明显提速 |
| P1-6/7 | clangd/rust-analyzer/LLDB 预编译 | L | 各语言工具链 optional 安装 | 补全/调试可用 |
| P1-8 | 移动端 UI 分叉（extra-keys/virtual mouse） | L | fork code-server web UI 或 CSS/JS 注入 | 触屏可用性明显提升 |
| P1-9 | CI 完整化（双 APK + emulator smoke） | L | 当前 CI 只有 debug+lint+unsigned release | PR 产出 online/offline + 测试报告 |

### 执行建议（原路线图结论）

- 顺序：清完 §7 P1 → 自编译 code-server（P1-2）为主线唯一 glibc 路线 → 16KB/离线 SDK 第二线。不要五条全开。proot（P1-1）不在主线排期。
- 决策门：自编译包体积/维护成本优于 TUR 才替换；16KB 失败即 P0。
- 禁忌：不要把 proot 引入主线（若立项独立版，先抽象 prefix/root 再动 pkg 层）；不要用 Microsoft Marketplace；不要为 16KB 直接换 bootstrap 不跑全回归；不要把 500MB 资产塞进默认 APK。

---

*交接+运维双用途。文档与代码冲突时，以当前代码和可复现实测为准，再更新本文档。*
