# LenuLane

<p align="center">
  <b>轻量网络加速与工具箱 · 双 L 双路径智能分流</b><br/>
  纯 Kotlin + Jetpack Compose 构建 · 雾蓝 `#53699A` / 浅蓝 `#8AA8DC` / `#E9EDF7` · RFC 7323 高吞吐进程内引擎
</p>

<p align="center">
  <a href="https://github.com/HLenu/WattKit/releases/latest"><b>下载最新 APK (v1.4.0)</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/HLenu/WattKit/releases">所有版本</a>
  &nbsp;·&nbsp;
  <a href="LICENSE">GPL-3.0 协议</a>
</p>

---

## 核心特性

- **双路径智能分流（Accel Lane / Direct Lane）**
  - 默认 `SMART_SPLIT` 模式仅接管加速域名的虚拟 DNS（`10.0.0.1/32`）与 `198.18.0.0/15` 加速轨，常规应用走物理网卡直连轨零损耗旁路；支持切换 `GLOBAL_TUN` 全局接管与 **分应用白名单/黑名单分流**。
  - 纯 Kotlin `TunPacketEngine` 支持 **RFC 7323 TCP 窗口缩放（最高 512 KB 滑动窗口）**、**后台主动 RTO 重传与僵尸会话回收**，结合 `Os.poll` + `Os.pipe()` 与 `Os.write()`，空载 CPU 占用 `0.0%`。
  - 同时提供本地 **SOCKS5 / HTTP CONNECT** 双协议代理端口（`127.0.0.1:28282`）。

- **DoH 净网、测速优选与自适应 TLS 分片**
  - 支持 **腾讯 DoH (`1.12.12.12`)**、**阿里 DoH (`223.5.5.5`)**、**360 DoH (`101.226.4.6`)** 与 **Cloudflare (`1.1.1.1`)**，提供 **一键测速优选**、512 条本地 DNS TTL 缓存、CDN Edge 兜底合并与 IP 级 EWMA 延迟排序。
  - 内置 22 组规则并支持 **用户自定义域名规则**；提供 **自适应 (`ADAPTIVE`) / TLS 记录层 / TCP 载荷层** 三种抗 DPI 分片策略，支持最多 3 节点快速故障转移。

- **独立 Steam Guard 令牌**
  - 支持导入 `.maFile` JSON、`otpauth://totp/...` 标准 URI 与批量 JSON 备份，支持一键导出备份 JSON，密钥经 Android Keystore `AES-256-GCM` 加密保存。
  - 含 `identity_secret` 的账号支持生成 **Valve 交易/市场确认 Hash (`conf`)**；官方校时独立于 VPN 开关运行。

- **链路诊断与实用工具**
  - **全链路加速雷达**：强制系统严格 CA 校验，实时验证目标站点的 DoH 解析 IP、TLS 版本与证书主题。
  - **Hosts 核查与一键生成**：支持扫描 `/system/etc/hosts` 冲突条目，并支持通过防污染 DoH 批量生成可复制的加速 Hosts 映射文本。

---

## 环境与规格

| 项目 | 说明 |
| :--- | :--- |
| 产品名称 | **LenuLane**（定位：轻量网络加速与工具箱） |
| 视觉标识 | 双 L · 双路径智能分流（主色雾蓝 `#53699A` · 辅色浅蓝 `#8AA8DC` · 背景 `#E9EDF7`） |
| 最新版本 | **v1.4.0** (`versionCode = 6`) |
| 最低支持 | Android 7.0+（API 24，compileSdk / targetSdk 34） |
| 架构与体积 | 纯 Kotlin 网络内核（已剔除无用视觉模型及相机权限，大幅缩减安装包体积） |
| UI 风格 | 冷雾蓝 `#E9EDF7` / `#FFFFFF` 卡片 + 悬浮液态玻璃底栏 |
| 单元测试 | 28 个纯 JVM JUnit 4 用例 |

---

## 本地构建

依赖 **JDK 17+** 与 **Android SDK 34**：

```bash
cd WattKit
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

Release 安装包输出在 `app/build/outputs/apk/release/app-release.apk`。如需正式签名，可通过环境变量传入签名信息（未配置时自动回退 debug 签名）：

```bash
export WATTKIT_STORE_FILE=/path/to/release.keystore
export WATTKIT_STORE_PASSWORD=******
export WATTKIT_KEY_ALIAS=your-alias
export WATTKIT_KEY_PASSWORD=******
```

---

## 版本记录

- **v1.4.0**：移除未使用的 ML Kit / CameraX 重型原生依赖与权限并清理遗留代码，APK 体积大幅瘦身；TUN 引擎支持 RFC 7323 TCP 窗口缩放（512KB 滑窗）、独立后台 RTO 重传与僵尸连接回收；新增 512 项 SmartDNS 缓存、360 DoH 与一键测速优选、Outline 自适应双模式 TLS 分片（记录层/TCP 载荷层）与 IP EWMA 延迟排序；新增分应用智能分流、自定义加速规则、`otpauth://` 导入与交易确认 Hash、以及加速 Hosts 生成导出。
- **[v1.2.2](https://github.com/HLenu/WattKit/releases/tag/v1.2.2)**：品牌升级为 **LenuLane**，实装双 L 立体交织桌面/状态栏图标及 **雾蓝 `#53699A` / 浅蓝 `#8AA8DC` / `#E9EDF7`** 视觉体系，首页新增双 L 徽标与双路径分流状态条。
- **[v1.2.1](https://github.com/HLenu/WattKit/releases/tag/v1.2.1)**：基于真机实测优化，`Os.poll` + `Os.pipe()` 消除 `/dev/tun` 空载 100% CPU 与 GC 抖动，修复 `VpnService.protect()` FD 绑定与 DoH 并发去重/污染过滤，完善有界线程池、TCP 重传重排与 360dp 竖屏排版。
- **[v1.2.0](https://github.com/HLenu/WattKit/releases/tag/v1.2.0)**：移除 `libtun2socks.so` 子进程，重构为纯 Kotlin 进程内 TUN + Fake-IP + DoH + TLS 分片引擎，解耦 Steam Guard 并接入全链路严格 TLS 诊断。
- **[v1.0.1](https://github.com/HLenu/WattKit/releases/tag/v1.0.1)**：加固 SNI 解析边界与 Keystore 加密存储，新增 CI 与单元测试。
- **[v1.0.0](https://github.com/HLenu/WattKit/releases/tag/v1.0.0)**：首个正式版本。

---

## 开源许可

基于 [GNU General Public License v3.0](LICENSE) 开源。致敬 Steam++ / Watt Toolkit 生态。
