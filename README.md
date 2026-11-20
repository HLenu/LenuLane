<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_lenulane_logo.png" width="92" alt="LenuLane Logo" />
</p>

<h1 align="center">LenuLane</h1>

<p align="center">
  <b>轻量 Android 网络加速与工具箱 · 双路径智能分流</b><br/>
  纯 Kotlin + Jetpack Compose · 零原生 `.so` 依赖 · 雾蓝 `#53699A` / 浅蓝 `#8AA8DC` / `#E9EDF7`
</p>

<p align="center">
  <a href="https://github.com/HLenu/LenuLane/releases/latest"><b>下载最新正式版 (v1.4.0)</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/HLenu/LenuLane/releases">全部版本</a>
  &nbsp;·&nbsp;
  <a href="LICENSE">GPL-3.0 协议</a>
</p>

---

## 项目简介

**LenuLane** 是一款面向 Android 平台的开源轻量网络加速与实用工具箱，标志由交织的**双 L**构成，象征产品的核心网络模型——**双路径智能分流（Dual-Lane Smart Split）**：

- **加速轨（Accel Lane · `#53699A`）**：仅对命中的目标域名分配 `198.18.0.0/15` Fake-IP，经进程内防污染 DoH 解析与自适应 TLS SNI 分片转发，绕过浅层 SNI 阻断与 DNS 污染，无需 Root 与自定义 CA 证书。
- **直连轨（Direct Lane · `#8AA8DC`）**：未命中加速规则的日常应用与国内流量直接通过设备物理网卡（Wi-Fi / 5G）原生直连，零额外延迟、不干扰常规联网。

---

## 核心功能

### 1. 进程内高吞吐 TUN 转发引擎
- **纯 Kotlin 网络内核（`TunPacketEngine`）**：不依赖重型 Go 运行时或外部 `tun2socks` 子进程，APK 体积仅 `~10.5 MB`，全架构通用且零 `.so` 崩溃风险。
- **RFC 7323 TCP 窗口缩放**：支持 `WSOPT` 协商，将单流滑动窗口上限由 `64 KB` 提升至 `512 KB`，显著提升跨境高 RTT 链路下的大文件与静态资源吞吐。
- **后台主动 RTO 与零空载功耗**：基于 `Os.poll` + `Os.pipe()` 阻塞唤醒与 `Os.write()` 直写，空载 CPU 占用 `0.0%`；内置 250ms 周期 RTO 超时重传与半开/僵尸会话回收。
- **双协议本地代理**：同步提供 `127.0.0.1:28282` 本地 **SOCKS5 / HTTP CONNECT** 监听端口。

### 2. 防污染 DoH、节点优选与自适应抗 DPI
- **4 组防污染 DoH 节点 + 一键测速优选**：内置 **腾讯 DoH (`1.12.12.12`)**、**阿里 DoH (`223.5.5.5`)**、**360 DoH (`101.226.4.6`)**、**Cloudflare (`1.1.1.1`)**，支持并发测速并自动切换最快节点。
- **SmartDNS 缓存与污染池过滤**：内置 `512` 条常规域名线格式 TTL 缓存；自动过滤私有/保留段与 GFW 劫持污染网段，合并精选 CDN Edge 候选池，并按 IP 级 EWMA 握手延迟动态排序。
- **自适应 TLS SNI 分片（`TlsFragmenter`）**：支持 **自适应（`ADAPTIVE`）/ TLS 记录层分片 / TCP 载荷层分片** 三种策略，最多 3 节点快速故障转移。

### 3. 规则管理与分应用分流
- **22 组内置规则 + 自定义域名规则**：覆盖 Steam、Pixiv、GitHub、Discord、Epic、EA、Ubisoft、Nexus Mods 等服务，支持按规则切换 **TLS 分片 / 镜像节点 / DoH 直连 / 自定义上游代理**，并支持自由增删自定义域名规则。
- **分应用智能分流**：支持 **全部应用 / 白名单（仅加速选中应用）/ 黑名单（绕过选中应用）** 三种分流范围。

### 4. 独立 Steam Guard 令牌
- **硬件级隔离加密**：密钥经 Android Keystore `AES-256-GCM` 加密保存，完全独立于 VPN 开关运行。
- **多格式导入与导出**：支持 `.maFile` JSON、标准 `otpauth://totp/...` URI、Base64 `shared_secret` 手动录入及批量 JSON 备份导出。
- **官方校时与交易确认签名**：通过独立 DoH + TLS 分片链路同步 Valve 官方时钟并持久化偏差；含 `identity_secret` 的账号支持一键生成并复制交易/市场确认 Hash（`conf`）。

### 5. 全链路诊断与 Hosts 工具箱
- **全链路加速雷达**：强制使用 Android 系统信任链严格校验目标站点的 DoH 解析 IP、TLS 版本与证书主题，拒绝虚假连通提示。
- **Hosts 核查与一键生成**：支持扫描 `/system/etc/hosts` 历史劫持冲突，并可通过当前防污染 DoH 批量生成标准加速 Hosts 映射文本一键复制。

---

## 技术规格

| 项目 | 说明 |
| :--- | :--- |
| 产品名称 | **LenuLane**（原名 WattKit，保持包名 `net.steampp.wattkit` 兼容覆盖升级） |
| 最新版本 | **v1.4.0** (`versionCode = 6`) |
| 系统要求 | Android 7.0+（`minSdk 24`，`compileSdk / targetSdk 34`） |
| 安装包体积 | `10.45 MB`（`10,960,451` 字节，无 `.so` / `.tflite` 原生冗余，无相机权限） |
| 视觉设计 | 雾蓝 `#53699A` · 浅蓝 `#8AA8DC` · 冷雾背景 `#E9EDF7` · 悬浮液态玻璃底栏 |
| 自动化测试 | 28 个纯 JVM JUnit 4 单元测试（SNI、TLS 分片、Fake-IP、DNS 缓存、TCP 校验与 WSOPT、TOTP） |

---

## 本地构建

环境依赖：**JDK 17+** 与 **Android SDK 34**。

```bash
git clone https://github.com/HLenu/LenuLane.git
cd LenuLane
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Release 安装包输出路径为 `app/build/outputs/apk/release/app-release.apk`。如需正式签名，可通过环境变量配置签名证书（未配置时自动回退 debug 签名）：

```bash
export WATTKIT_STORE_FILE=/path/to/release.keystore
export WATTKIT_STORE_PASSWORD=******
export WATTKIT_KEY_ALIAS=your-alias
export WATTKIT_KEY_PASSWORD=******
```

---

## 版本历史

- **[v1.4.0](https://github.com/HLenu/LenuLane/releases/tag/v1.4.0)**：清理未使用的 ML Kit / CameraX 依赖与相机权限，APK 瘦身 `52.3%`；TUN 引擎支持 RFC 7323 512KB 滑窗、独立后台 RTO 重传与僵尸回收；新增 512 项 SmartDNS 缓存、360 DoH 与一键测速优选、自适应双模式 TLS 分片、IP EWMA 延迟排序与扩展污染过滤；新增分应用分流、自定义规则、`otpauth://` 导入与交易确认 Hash、以及加速 Hosts 生成导出。
- **[v1.2.2](https://github.com/HLenu/LenuLane/releases/tag/v1.2.2)**：品牌焕新为 **LenuLane**，实装双 L 图标及雾蓝 `#53699A` / 浅蓝 `#8AA8DC` / `#E9EDF7` 色彩体系，首页新增双路径分流指示条。
- **[v1.2.1](https://github.com/HLenu/LenuLane/releases/tag/v1.2.1)**：`Os.poll` + `Os.pipe()` 消除 `/dev/tun` 空载 CPU 占满与 GC 抖动，修复 `VpnService.protect()` FD 绑定与 DoH 并发去重。
- **[v1.2.0](https://github.com/HLenu/LenuLane/releases/tag/v1.2.0)**：移除 `libtun2socks.so` 子进程，重构为纯 Kotlin 进程内 TUN + Fake-IP + DoH + TLS 分片引擎并解耦 Steam Guard。
- **[v1.0.1](https://github.com/HLenu/LenuLane/releases/tag/v1.0.1)**：加固 SNI 解析与 Keystore 加密存储，接入 CI 与单元测试。
- **[v1.0.0](https://github.com/HLenu/LenuLane/releases/tag/v1.0.0)**：首个正式版本。

---

## 开源许可

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。
