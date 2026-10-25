# AMCode 扩展路线图与重任务清单

> 基线：`ef2b56c` / 交接文档 `eb52968`  
> 目的：列出当前 backlog 之外的优化点，特别是值得用大量 token 做的“重任务”（编译、打包、架构改造）。  
> 评分：**优先级 P0–P3**；**难度 S/M/L/XL**；**规模** 指工程量，不等于 token 量。

---

## 0. 结论先行

当前最值得投入的三个“重任务”：

1. **Proot/glibc 兼容模式**（XL）：真正解决大多数 Marketplace/VSIX native 扩展，代价是 rootfs 体积和性能。
2. **自编译 Android-native code-server + 原生模块**（XL）：摆脱 TUR 包、修 spdlog/watcher/node-pty/ripgrep 的 bionic ABI 问题，缩小首次下载。
3. **16KB page-size 重建 + Android 16 回归**（L/XL）：现在不做，未来新设备可能直接跑不起来。

其余重任务：离线 SDK/自定义 bootstrap、TUR 镜像/CDN、clangd/rust-analyzer/LLDB 预编译、移动端 VS Code UI 分叉、GeckoView 实验。

**顺序建议**：先清 P0 发布/安全/API 回归，再并行开 proot 与 custom code-server 两条重线；16KB 与离线 SDK 作为第三条线。不要一上来同时开五条，构建/测试会互相抢资源。

---

## 1. 评分与判断标准

| 等级 | 含义 |
|---|---|
| **P0** | 发布/安全/数据正确性，必须先做 |
| **P1** | 用户强感知，或能解锁一整类功能 |
| **P2** | 体验/性能/可维护性优化 |
| **P3** | 研究型，可能失败，但失败也有结论 |

| 难度 | 典型工作量 |
|---|---|
| **S** | 单文件/单命令，半天内 |
| **M** | 多文件 + 设备验证，1–2 天 |
| **L** | 需要构建/打包/跨架构，3–7 天 |
| **XL** | 多阶段流水线/新运行模式，1–3 周+ |

---

## 2. 任务总表

### P0：发布前必须做

| ID | 任务 | 难度 | 依赖 | 验收 |
|---|---|---|---|---|
| P0-1 | release fresh 安装回归（online + offline arm64） | M | 双 APK | fresh AVD 安装、auth/focus/pkg/internal HOME 全通过 |
| P0-2 | release keystore 轮换、密码移出仓库、push 前审计 | M | — | 旧 keystore 不再可用；仓库无明文密码 |
| P0-3 | versionCode bump + README/ARCHITECTURE 更新 | S | — | 版本号递增；文档与 internal HOME/auth/双 APK 一致 |
| P0-4 | internal workspace Trust 一键提示 | M | MainActivity | 首次 internal 明确提示，点一次 Trust 后扩展启用 |
| P0-5 | API 26/29 存储回归 | M | API28 AVD | grant/deny/fallback/code-server ready 均通过 |
| P0-6 | `files.watcherExclude` 排除 `~/storage/shared` | S | — | watcher 不跟随 /sdcard；CPU 无明显上升 |
| P0-7 | 显式 external 切换 UI + noexec 警告 | M | Storage action | 可切回 `/sdcard/AMCode`，文件不丢 |
| P0-8 | Git 安装/扩展体验 | S/M | main 镜像 | `git --version` 可用；Git 扩展不再 not found |

### P1：重任务/高价值

| ID | 任务 | 难度 | 依赖 | 验收 |
|---|---|---|---|---|
| P1-1 | **Proot/glibc 兼容模式** | XL | proot + rootfs | 能跑 glibc native 扩展；终端/文件共享正常；性能可接受 |
| P1-2 | **自编译 Android-native code-server + native modules** | XL | NDK/Termux clang | spdlog/watcher/node-pty/ripgrep 无 ABI 错误；不依赖 TUR |
| P1-3 | **16KB page-size bootstrap/node/native modules** | L/XL | 16KB 设备/API36 | `readelf` 对齐 0x4000；API35/36 可启动 |
| P1-4 | **离线 SDK/自定义 bootstrap** | L/XL | 预编译包 | fresh 安装 <1–2 分钟、零 TUR；APK/asset 可控 |
| P1-5 | TUR/GitHub 镜像 + CDN + 断点/差分更新 | L | 托管 | 无代理下载明显提速；失败可回退 |
| P1-6 | 预编译语言工具链（clangd/pyright/rust-analyzer/gopls） | L | 交叉编译 | 各语言补全/诊断可用；可选安装 |
| P1-7 | LLDB/GDB + DAP 调试 | L | 16KB/clang | C/C++ 断点、变量、栈可用 |
| P1-8 | 移动端 VS Code UI 分叉（extra keys/virtual mouse） | L | code-server fork | 触屏编辑/终端可用性明显提升 |
| P1-9 | CI：双 APK 构建 + API28/34 emulator smoke | L | GitHub Actions | 每次 PR 产出 online/offline + 测试报告 |

### P2：体验/工程优化

| ID | 任务 | 难度 | 依赖 | 验收 |
|---|---|---|---|---|
| P2-1 | 旧安装 code-server/node 补登记 dpkg DB | M | TermuxManager | 旧数据 `dpkg -l` 正确；audit 空 |
| P2-2 | API28 py3compile 兼容（seccomp/compileall） | M | API28 | Python 预编译完整；audit 空 |
| P2-3 | 在线下载进度 UI/错误分诊/重试 | M | downloader | 用户能看到包/速度/失败原因 |
| P2-4 | 包管理器 UI + 本地离线仓库 | M | pkg | 搜索/安装/卸载/修复；无网可用 |
| P2-5 | SAF 导入导出 + 共享目录权限流程 | M | MainActivity | 无 MANAGE 时按需授权/拒绝/重试 |
| P2-6 | 多窗口/桌面模式/外接显示器 | M | Android | 分屏/自由窗口下键盘/布局正常 |
| P2-7 | 性能 profiling（启动/内存/CPU） | M | baseline profile | 冷启动/内存有基线；无回归 |
| P2-8 | 安全扫描/依赖审计/日志脱敏 | M | CI | 无 token/密码泄漏；依赖有报告 |
| P2-9 | 诊断/修复/重置/日志导出 | S/M | — | 用户可一键修复环境/导出日志 |
| P2-10 | extension 兼容矩阵 + 自动 smoke | M | shim/proot | 常见扩展列表通过/失败有记录 |
| P2-11 | release 更新通道/版本检查 | M | GitHub release | 可选更新提示；不阻塞启动 |
| P2-12 | 16KB 兼容的 proot/talloc 构建 | M | P1-3 | proot 在 16KB 设备可启动 |

### P3：研究型（可失败）

| ID | 任务 | 难度 | 说明 |
|---|---|---|---|
| P3-1 | GeckoView/其他 WebView 引擎替代 | XL | 可能改善 IME/输入，但兼容性和体积风险大 |
| P3-2 | 完整 VS Code server 源码分叉（非 code-server） | XL | 维护成本极高；先评估 license/更新 |
| P3-3 | 把 proot 设为默认运行模式 | XL | 取决于 P1-1 的性能/体积结论 |
| P3-4 | Android 16 targetSdk 合规/上架 | XL | targetSdk 28 是 W^X 规避；上架需完全不同架构 |
| P3-5 | 自托管 Marketplace/VSIX 代理 | L | 法务/带宽/缓存；Open VSX 优先 |

---

## 3. 重任务展开

### 3.1 P1-1 Proot/glibc 兼容模式（最值得砸）

**目标**：保留现有 bionic 快速模式，新增可选 “compat mode”：在 app 内跑一个 Debian/Ubuntu arm64 rootfs，通过 proot 运行 code-server，使 glibc native 扩展可用。

**做法**：

1. 用 debootstrap 构建最小 Debian arm64 rootfs（bookworm/trixie），只装 code-server 运行依赖 + git + clang 可选。
2. proot + talloc 用 Termux/NDK 交叉编译，重点验证 **16KB page size**、seccomp、`/proc`、`/dev`、`/sdcard` bind mount。
3. rootfs 放 asset（压缩），首次解压到 app data；或作为 optional asset pack。
4. 启动：`proot -r rootfs -b /sdcard:/sdcard -b $HOME ... code-server --bind-addr 127.0.0.1`。
5. App 的 WebView/auth/focus 复用；HOME/workspace 通过 bind mount。
6. 提供 native/compat 模式切换；compat 模式失败可回退。

**验收**：

- 安装并运行一个带 glibc native 的扩展（如 C/C++/语言 server）。
- 终端、文件、Git、端口转发正常。
- 冷启动/内存有数据；明确可接受才考虑默认。

**风险**：rootfs 300–800MB；proot 性能损耗；Android 16/seccomp；license/分发；16KB proot。

### 3.2 P1-2 自编译 Android-native code-server（第二值得）

**目标**：不再依赖 TUR 的 code-server 包，自己产出可复现的 Android/bionic 构建。

**做法**：

1. 固定 code-server 4.143.1 / VS Code server 源码；打 Android 补丁（路径/平台 shim/native spawn）。
2. 用 Termux clang/NDK 编译 `@vscode/spdlog`、`@parcel/watcher`、`node-pty`、`ripgrep` 等 native 模块，链接 libc++30。
3. 产出 `.deb`/tar/asset；内置 `android-as-linux.js` 与 ripgrep 修复。
4. 打包为可选 `code-server-android` 组件；online/offline 都能装。
5. CI 里固定 toolchain/hash，保证可复现。

**验收**：

- 扩展宿主无 spdlog/watcher ABI 错误；`rg` 可用；纯 JS + bionic native 扩展正常。
- 包体积小于 TUR 的 120MB（目标 80–100MB）。
- 可替换 TUR 包且 auth/focus 不回归。

**风险**：VS Code server 构建复杂；native 模块 ABI;license/Open VSX；升级维护成本。

### 3.3 P1-3 16KB page-size（未来硬门槛）

**背景**：Android 15/16 的 16KB page 设备上，4KB-aligned ELF 可能无法加载；当前 bootstrap/node/native 模块未验证。

**做法**：

- 用 `-Wl,-z,max-page-size=16384` 重建 bootstrap 关键二进制、node、native 模块、proot。
- 检查 `readelf -l` 的 LOAD align；补 `libc++_shared`/linker。
- 在 API35/36 16KB AVD/真机跑 smoke：解压、pkg、code-server、扩展、终端。
- CI 加 `readelf` 检查。

**验收**：16KB 设备能启动 code-server；pkg 安装/执行正常；记录哪些包仍需 4KB。

### 3.4 P1-4 离线 SDK/自定义 bootstrap

**目标**：把 code-server/node/git/clang/python/常用工具预装进 bootstrap 或 optional asset，彻底去掉首次 139MB 下载。

**选项**：

- A：单一超大 APK（200–400MB），简单但分发重。
- B：base APK + asset pack/额外 zip（推荐）；首次本地解压，无网络。
- C：按需下载工具链（clang/python），核心 code-server 预装。

**验收**：fresh 安装 <1–2 分钟、零 TUR；pkg 仍可用；APK/asset 大小可接受。

### 3.5 P1-5 TUR/GitHub 镜像/CDN

- 自建 GitHub Releases/Cloudflare R2/对象存储镜像，缓存固定版本 deb。
- App 增加多源+测速+断点+差分（bsdiff/zstd）。
- 保留 TUR 官方源回退；不要编造不存在的公共镜像。

### 3.6 P1-6/7 工具链与调试

- 预编译 clangd、pyright、rust-analyzer、gopls、debugpy；按语言 optional 安装。
- LLDB/GDB 交叉编译 + DAP，验证 C/C++ 断点/变量/调用栈。
- 这部分能显著提升“像桌面 VS Code”的体验。

### 3.7 P1-8 移动端 UI 分叉

- extra keys row（Esc/Tab/Ctrl/Alt/方向）、virtual mouse/trackpad、触摸滚动、键盘 toolbar。
- 可 fork code-server 的 web UI 或注入 CSS/JS；先做轻量注入，再考虑完整分叉。
- 与 focus/IME 修复一起做回归。

---

## 4. 执行波次

### Wave 0（先做，1–2 天）

- P0-1 release fresh
- P0-2 keystore/push
- P0-3 versionCode/README
- P0-4 Trust 提示
- P0-5 API26/29
- P0-6 watcherExclude
- P0-8 Git

### Wave 1（重任务并行，2–4 周）

三条线并行，互不抢同一文件：

| 线 | 任务 | 负责范围 |
|---|---|---|
| **Compat** | P1-1 proot/glibc | rootfs/proot/启动脚本/资产 |
| **Native** | P1-2 自编译 code-server | build/CI/native modules |
| **Future** | P1-3 16KB + P1-4 离线 SDK | bootstrap/asset pack/API36 |

主 agent 只做集成、构建、设备测试；不要三条线同时改 `MainActivity`/`TermuxManager`。

### Wave 2（3–6 周）

- P1-5 镜像/CDN
- P1-6 工具链
- P1-7 LLDB
- P1-8 UI 分叉
- P1-9 CI
- P2 系列

### Wave 3（研究）

- P3-1 GeckoView
- P3-2 完整 VS Code server 分叉
- P3-3 proot 默认
- P3-4 targetSdk/上架

---

## 5. Token / 并行分配建议

如果 token 很充足，建议按“重任务 60%、收尾 25%、研究 15%”：

| 方向 | 占比 | 说明 |
|---|---|---|
| P0 收尾 | 10% | release/keys/docs/Trust/API |
| Proot compat | 30% | 最大兼容性收益 |
| Native code-server | 25% | 去掉 TUR/ABI 根因 |
| 16KB + 离线 SDK | 20% | 未来兼容 + 首次安装 |
| 工具链/UI/CI | 15% | 体验/工程化 |

**并行上限**：3 条重线 + 1 个 QA；每条线单独构建目录/APK，避免 Gradle 缓存和输出互相覆盖。

---

## 6. 决策门

1. **Proot 性能**：冷启动/内存/终端延迟可接受才考虑默认；否则只做 optional compat mode。
2. **自编译 code-server 体积/维护成本**：小于 TUR 包并稳定才替换；否则并行提供。
3. **16KB**：API35/36 设备不能启动就是 P0，不是可选。
4. **APK 体积**：base 保持 ~68MB；offline/compat 用 optional asset pack，避免所有人被迫下载 500MB。
5. **Marketplace/法务**：继续 Open VSX/VSIX；不要集成 Microsoft Marketplace。
6. **安全**：release keystore 先轮换；proot/rootfs 也要签校验。

---

## 7. 立即可安排给 zcode 的任务包

### 包 A：发布收尾（1 个 agent，1–2 天）

- release fresh 安装 online/offline
- keystore 轮换/push
- versionCode/README/ARCHITECTURE
- Trust 提示 + API26/29 + watcherExclude

### 包 B：Proot 可行性 spike（1 个 agent，3–5 天）

- 构建最小 Debian arm64 rootfs
- 交叉编译 proot/talloc（先 4KB，再 16KB）
- 在 5558 上跑 code-server，装一个 glibc native 扩展
- 输出性能/体积/失败点报告；不急着接主 App

### 包 C：Native code-server spike（1 个 agent，3–5 天）

- 固定源码/toolchain
- 编译 spdlog/watcher/node-pty/ripgrep
- 产出可替换包，测 extension host
- 输出可复现构建脚本

### 包 D：16KB + 离线 SDK（1 个 agent，3–5 天）

- 检查现有 ELF align
- 重建 node/bootstrap 关键件
- 16KB AVD/真机 smoke
- 评估 custom bootstrap 体积/解压时间

### 包 E：CI/工具链（后续）

- GitHub Actions 双 APK
- API28/34 emulator smoke
- clangd/LLDB 预编译

---

## 8. 风险与不要做的事

- 不要在没有基准的情况下把 proot 设为默认；先用 optional。
- 不要同时改 `TermuxManager` 的 pkg 修复和 proot rootfs 安装路径；先抽象 prefix/root。
- 不要用 Microsoft Marketplace；不要打包未授权二进制。
- 不要为了 16KB 直接替换 bootstrap 而不跑 full regression；pkg/dpkg 最容易被破坏。
- 不要把 190MB/500MB 资产塞进默认 APK；用 flavor/asset pack。
- 不要忘了 release keystore 轮换和 token 日志脱敏。

---

*本路线图是候选池，不是承诺；每个 spike 结束都要用数据决定是否进入主线，并回写 AMCODE_HANDOVER.md。*
