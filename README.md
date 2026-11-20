# WattKit (Android)

> **Modern iOS Cupertino Style Steam Toolkit & Pure-Kotlin Network Accelerator for Android**
> 基于纯 Kotlin + Jetpack Compose 构建，在 **v1.2.0** 彻底重构网络引擎，实现进程内智能 Fake-IP 分流、DoH 抗污染解析与 RFC TLS SNI 记录分片。

<p align="center">
  <b>最新版本 v1.2.0</b> · <a href="https://github.com/HLenu/WattKit/releases/latest">下载 / Release Notes</a> · <a href="LICENSE">GPL-3.0</a>
</p>

| 项目 | 说明 |
| :--- | :--- |
| 当前版本 | **v1.2.0**（versionCode 3） |
| 网络内核 | 纯 Kotlin 进程内 `TunPacketEngine`（已移除旧版 `libtun2socks.so` 子进程） |
| 支持架构 | 全架构纯 Kotlin + Jetpack Compose（兼容 Android 15+ 16KB Page Size） |
| 系统要求 | Android 7.0+（API 24），compileSdk / targetSdk 34 |
| 开源许可 | GNU GPL v3.0 |

---

## 📦 下载与安装

| 版本 | 类型 | 说明 |
| :--- | :--- | :--- |
| [**v1.2.0**](https://github.com/HLenu/WattKit/releases/tag/v1.2.0) | 预编译 APK | **最新重构版**，彻底解决 VPN 断网问题，支持直接覆盖升级 v1.0.0（`WattKit-v1.2.0-release.apk`） |
| [**v1.0.1**](https://github.com/HLenu/WattKit/releases/tag/v1.0.1) | 历史版本 | SNI 解析与密钥存储加固版本（debug 签名，仍采用旧版 tun2socks 架构） |
| [**v1.0.0**](https://github.com/HLenu/WattKit/releases/tag/v1.0.0) | 历史版本 | 首个正式版本 |

---

## 🔧 为什么在 v1.2.0 彻底重构网络引擎？

通过源码、原生二进制分析及真机实测，确认 `v1.0.0` 与 `v1.0.1` 开启 VPN 即全机断网由以下架构根因共同导致：
1. **DNS / UDP 全机黑洞**：`0.0.0.0/0` 路由劫持全部流量但未调用 `addDnsServer()`、未配置 `tun2socks --dnsgw` / UDP 转发，本地代理也拒绝 `UDP ASSOCIATE`，导致系统全部 UDP/53 域名解析请求进入 `tun0` 后被丢弃。
2. **TUN 接口 IP 冲突**：Android `tun0` (`10.0.0.2`) 与 `libtun2socks.so --netif-ipaddr 10.0.0.2` 相同，lwIP 协议栈回包判定目标为本机环回，无法正常完成 TCP 握手。
3. **跨进程 FD 交接与假规则问题**：子进程 `ProcessBuilder` + `LocalSocket` 轮询传递 FD 脆弱易死锁；原 22 条规则中多数无 `targetHost` 且部分镜像域名在严格 TLS 校验下触发证书不匹配。

因此 **v1.2.0** 移除 `libtun2socks.so` 二进制子进程，升级为 **100% 纯 Kotlin 进程内用户态引擎 + DoH + TLS 记录分片**：

---

## ✨ v1.2.0 核心特性

- 🚀 **纯 Kotlin 进程内 TUN 引擎与 Fake-IP 智能分流**
  - **智能规则分流 (`SMART_SPLIT`，默认)**：仅路由 `10.0.0.1/32` 虚拟 DNS 与 `198.18.0.0/15`（RFC 2544）加速网段；日常国内与未匹配流量获得真实 IP 后 100% 走物理网卡内核直连，开启加速 **绝不断网**。支持一键切换「全局 TUN 接管」。
  - **防绕过与自动回退**：`SmartDnsEngine` 对加速域名返回合成 `Fake-IP` 并对 `AAAA` / `HTTPS` / `SVCB` 返回 `NOERROR NODATA`；对加速 Fake-IP 的 `UDP/443` (QUIC) 请求立即回写 ICMP Port Unreachable，促使客户端毫秒级切回 TCP/443。
  - **网络切换自愈**：集成 `ConnectivityManager.NetworkCallback`，Wi-Fi 与蜂窝切换时自动更新底层物理网络与上游 DNS 列表并刷新缓存。
  - **双协议本地代理**：`127.0.0.1:28282` 同时支持 **SOCKS5** 与 **HTTP CONNECT** 代理隧道，共享统一调度器。

- 🛡️ **抗污染 DoH + RFC TLS 记录分片 & 多策略节点管理**
  - **防污染 DoH 解析 (`DoHResolver`)**：支持切换 **腾讯 DoH (`1.12.12.12`)**、**阿里 DoH (`223.5.5.5`)** 与 **Cloudflare (`1.1.1.1`)**，内置常见污染黑洞 IP 过滤与验证过的 CDN Anycast 回退池。
  - **RFC 5246/8446 TLS SNI 记录分片 (`TlsFragmenter`)**：定位 ClientHello 内 SNI 字节区间并在中点拆分为多条合法 TLS Record 配合 `TCP_NODELAY` 发送，不注入系统根证书即可通过 Steam、Pixiv、GitHub、Discord 官方严格 TLS 1.3 证书校验。
  - **四维规则策略与实时遥测**：支持为 22 条规则按需切换「TLS 分片 / 镜像节点 / DoH 直连 / 自定义上游代理 (SOCKS5/HTTP)」，并实时记录每条规则的命中次数、连接日志、节点 IP 与握手延迟。

- 🔐 **解耦的 Steam Guard 双因素认证器**
  - **独立时钟校准与持久化**：Valve `ITwoFactorService/QueryTime` 时钟同步独立走 DoH + TLS 分片链路并持久化时差，不受 VPN 开关或断网状态影响。
  - **硬件级密文存储**：使用 Android Keystore AES-256-GCM 加密 `shared_secret`，清理空状态强制写入假账号逻辑，仅提供显式的可选测试向量加载按钮。

- 📡 **真实全链路加速雷达 & Hosts 核查**
  - **零绕过严格 TLS 验证**：移除旧版 `.hostnameVerifier { _, _ -> true }` 伪绿灯，并发实测 6 大核心服务节点，展示 DoH 解析 IP、实际 TLS 版本、证书 CN 与 HTTP 状态码。
  - **真实系统 Hosts 扫描**：直接核查 `/system/etc/hosts` 是否有 Steam/Pixiv/GitHub 历史冲突映射。

- 🎨 **iOS 26 Cupertino 浅色视觉系统**
  - 遵循 `#F2F2F7` 分组底色 + `#FFFFFF` 纯白卡片容器、Liquid Glass 底部悬浮导航栏与 iOS 原生视差切页动效。

---

## 🧪 单元测试覆盖

项目包含 21 个纯 JVM JUnit 4 单元测试：
- `SniSnifferTest`（10 个用例）：TLS ClientHello SNI 提取、恶意越界/畸形记录防御与字节区间定位。
- `TlsFragmenterTest`（3 个用例）：验证分片后每条 TLS Record 长度精确、单包不泄露完整 SNI 且重组字节 100% 还原。
- `DnsAndFakeIpTest`（4 个用例）：验证 `FakeIpPool` 分配与反查、`DnsPacketCodec` 报文编解码及污染 IP 过滤。
- `PacketChecksumAndTunTest`（4 个用例）：验证 RFC 1071 IPv4/UDP/TCP/ICMP 校验和及 ICMP Echo / Port Unreachable 报文构造。

---

## 🛠️ 构建与编译

### 编译依赖
- Android SDK 34 (API 34, Build Tools 34.0.0)
- JDK 17+（推荐 Eclipse Temurin 或 Azul Zulu JDK 21）

> 项目自带标准 Gradle Wrapper（8.9），克隆后无需全局安装 Gradle，直接运行 `./gradlew`（Windows 使用 `gradlew.bat`）。

### 签名配置（可选）
Release 默认优先读取 `WATTKIT_*` 环境变量或本地开发 keystore，若均不存在则自动回退 `debug` 签名以保证仓库开箱可编：

```bash
export WATTKIT_STORE_FILE=/path/to/release.keystore
export WATTKIT_STORE_PASSWORD=******
export WATTKIT_KEY_ALIAS=your-alias
export WATTKIT_KEY_PASSWORD=******
```

### 测试与构建命令
```bash
cd WattKit
./gradlew testDebugUnitTest assembleDebug assembleRelease
```
Release 编译产物位于 `app/build/outputs/apk/release/app-release.apk`。

---

## 📝 更新日志

### v1.2.0
- **网络引擎重构**：移除 `libtun2socks.so` 子进程及 JNI SO 依赖，改为纯 Kotlin 进程内 `TunPacketEngine` + `SmartDnsEngine` + `FakeIpPool` + `PacketChecksum`
- **彻底修复开启 VPN 断网**：默认启用 RFC 2544 Fake-IP 智能分流路由（`10.0.0.1/32` + `198.18.0.0/15`），日常域名直走物理网卡，加速域名经 `NOERROR NODATA` (`AAAA`/`HTTPS`) 与 QUIC ICMP Port Unreachable 回退稳定接入加速隧道
- **DoH + TLS SNI 记录分片**：新增 `DoHResolver`（支持腾讯 DoH / 阿里 DoH / Cloudflare 与污染 IP 过滤）与 `TlsFragmenter`（ClientHello SNI 中点拆分），支持官方站点严格 CA 验证直连
- **节点策略、上游代理与实时遥测**：22 条规则升级为可用策略并持久化策略选择；新增自定义上游 SOCKS5/HTTP 代理支持、`TrafficTelemetry` 实时命中计数与 60 条连接日志
- **Steam Guard 解耦 & 真实链路雷达**：时钟同步持久化且独立于 VPN 状态；移除空白自动塞入假账号行为；诊断雷达强制严格证书链校验并新增真实 `/system/etc/hosts` 扫描
- **单元测试完善**：由 9 个单测扩展至 21 个 JUnit 4 核心单测并同步 CI (`platforms;android-34`)

### v1.0.1
- 加固 `SniSniffer` 边界校验并引入 `SniSnifferTest` 与 GitHub Actions CI
- `shared_secret` 改为 Android Keystore AES-256-GCM 加密落盘，补齐 `VpnService.onRevoke()`、标准 Gradle Wrapper 与规则开关持久化

### v1.0.0
- 首个正式版本：纯 Kotlin + Jetpack Compose 原生实现、iOS 26 Cupertino 浅色视觉、Liquid Glass 悬浮 Dock、22 项加速规则与 Steam Guard 令牌

---

## 📄 开源许可

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源协议。
致敬原版 Steam++ / Watt Toolkit 开源生态。
