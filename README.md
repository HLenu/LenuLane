# LenuLane

<p align="center">
  <b>轻量网络加速与工具箱 · 双 L 双路径智能分流</b><br/>
  纯 Kotlin + Jetpack Compose 构建 · 雾蓝 `#53699A` / 浅蓝 `#8AA8DC` / `#E9EDF7` · 进程内 Fake-IP 分流
</p>

<p align="center">
  <a href="https://github.com/HLenu/WattKit/releases/latest"><b>下载最新 APK (v1.2.2)</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/HLenu/WattKit/releases">所有版本</a>
  &nbsp;·&nbsp;
  <a href="LICENSE">GPL-3.0 协议</a>
</p>

---

## 核心特性

- **双路径智能分流（Accel Lane / Direct Lane）**
  - 默认 `SMART_SPLIT` 模式仅接管加速域名的虚拟 DNS（`10.0.0.1/32`）与 `198.18.0.0/15` 加速轨，常规应用走物理网卡直连轨零损耗旁路；支持一键切换 `GLOBAL_TUN` 全局接管。
  - 纯 Kotlin `TunPacketEngine` 基于 `Os.poll` + `Os.pipe()` 阻塞唤醒，无原生 `libtun2socks.so` 依赖，空载 CPU 占用 `0.0%`。
  - 同时提供本地 **SOCKS5 / HTTP CONNECT** 双协议代理端口（`127.0.0.1:28282`）。

- **DoH 净网与 TLS SNI 分片**
  - 支持 **腾讯 DoH (`1.12.12.12`)**、**阿里 DoH (`223.5.5.5`)** 与 **Cloudflare (`1.1.1.1`)**，内置污染 IP 过滤、并发去重及 CDN 回退池。
  - 内置 22 组规则（Steam、Pixiv、GitHub、Discord 等），支持按规则切换 **TLS 分片 / 镜像节点 / DoH 直连 / 自定义上游代理** 四种策略，配合实时命中与连接日志。

- **独立 Steam Guard 令牌**
  - 支持导入 `.maFile` 或手动输入 Base64 `shared_secret`，密钥经 Android Keystore `AES-256-GCM` 加密保存。
  - Valve 官方校时走独立加密链路并持久化偏差，离线或关闭加速时仍正常生成验证码。

- **链路诊断与系统工具**
  - **全链路加速雷达**：强制系统严格 CA 校验，实时验证目标站点的 DoH 解析 IP、TLS 版本与证书主题。
  - **系统 Hosts 核查**：直接扫描 `/system/etc/hosts` 排查历史静态规则冲突。

---

## 环境与规格

| 项目 | 说明 |
| :--- | :--- |
| 产品名称 | **LenuLane**（定位：轻量网络加速与工具箱） |
| 视觉标识 | 双 L · 双路径智能分流（主色雾蓝 `#53699A` · 辅色浅蓝 `#8AA8DC` · 背景 `#E9EDF7`） |
| 最新版本 | **v1.2.2** (`versionCode = 5`) |
| 最低支持 | Android 7.0+（API 24，compileSdk / targetSdk 34） |
| 架构支持 | 网络核心纯 Kotlin，通用架构 |
| UI 风格 | 冷雾蓝 `#E9EDF7` / `#FFFFFF` 卡片 + 悬浮液态玻璃底栏 |
| 单元测试 | 23 个纯 JVM JUnit 4 用例 |

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

- **v1.2.2**：品牌升级为 **LenuLane**，实装双 L 立体交织桌面/状态栏图标及 **雾蓝 `#53699A` / 浅蓝 `#8AA8DC` / `#E9EDF7`** 视觉体系，首页新增双 L 徽标与双路径分流状态条。
- **[v1.2.1](https://github.com/HLenu/WattKit/releases/tag/v1.2.1)**：基于真机实测优化，`Os.poll` + `Os.pipe()` 消除 `/dev/tun` 空载 100% CPU 与 GC 抖动，修复 `VpnService.protect()` FD 绑定与 DoH 并发去重/污染过滤，完善有界线程池、TCP 重传重排与 360dp 竖屏排版。
- **[v1.2.0](https://github.com/HLenu/WattKit/releases/tag/v1.2.0)**：移除 `libtun2socks.so` 子进程，重构为纯 Kotlin 进程内 TUN + Fake-IP + DoH + TLS 分片引擎，解耦 Steam Guard 并接入全链路严格 TLS 诊断。
- **[v1.0.1](https://github.com/HLenu/WattKit/releases/tag/v1.0.1)**：加固 SNI 解析边界与 Keystore 加密存储，新增 CI 与单元测试。
- **[v1.0.0](https://github.com/HLenu/WattKit/releases/tag/v1.0.0)**：首个正式版本。

---

## 开源许可

基于 [GNU General Public License v3.0](LICENSE) 开源。致敬 Steam++ / Watt Toolkit 生态。
