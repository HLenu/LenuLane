# WattKit (Android)

> **Modern iOS Cupertino Style Steam Toolkit & Network Accelerator for Android**
> 基于纯 Kotlin + Jetpack Compose 从零重构，全面对齐 2026 年 Android 15+ 强制 16KB Page Size 原生内存标准。

<p align="center">
  <b>最新版本 v1.0.1</b> · <a href="https://github.com/HLenu/WattKit/releases/latest">下载 / Release Notes</a> · <a href="LICENSE">GPL-3.0</a>
</p>

| 项目 | 说明 |
| :--- | :--- |
| 当前版本 | **v1.0.1**（versionCode 2） |
| 支持架构 | `arm64-v8a` / `armeabi-v7a` / `x86_64` |
| 系统要求 | Android 7.0+（API 24），targetSdk 35 |
| 开源许可 | GNU GPL v3.0 |

---

## 📦 下载与安装

| 版本 | 类型 | 说明 |
| :--- | :--- | :--- |
| [**v1.0.1**](https://github.com/HLenu/WattKit/releases/tag/v1.0.1) | 预编译 APK | 最新版本，附带 `WattKit-v1.0.1-release.apk`（debug 签名，与 v1.0.0 签名不同，覆盖安装需先卸载） |
| [**v1.0.0**](https://github.com/HLenu/WattKit/releases/tag/v1.0.0) | 预编译 APK | 首个正式版本，附带 `WattKit-v1.0.0-release.apk` |

---

## ✨ 核心特性

- 🎨 **iOS 26 Cupertino 视觉系统**
  - **浅色纯白底色（Light Mode）**：遵循 Apple Human Interface Guidelines，采用 `#F2F2F7` 分组底色与纯白 `#FFFFFF` 悬浮卡片。
  - **Liquid Glass 悬浮 Dock**：Apple Music 风格的半透明磨砂亚克力底部导航栏，提供 0.5dp 微光边框与弹性触控反馈。
  - **原生 iOS 视差切页动效**：横向视差滑动与 Spring 阻尼动效，切换丝滑跟手。
  - **Cupertino 原生组件库**：51×31dp 弹簧阻尼开关（CupertinoSwitch）、Inset Grouped 分组容器与高对比度列表。

- ⚡ **全生态免证书 SNI 网络加速引擎**
  - **7 大板块、22 项主流规则**：全面适配 Steam 社区、Steam 商店、Pixiv（主站与 pximg 原图）、Epic Games、EA/Origin、Ubisoft、Rockstar、Riot、GOG、NexusMods、CurseForge、GitHub、Discord、Twitch、Spotify 等服务。
  - **零侵入免证书**：利用本地 TCP 嗅探 TLS 握手 ClientHello SNI，在保证端到端加密安全的前提下实现反向代理，**无需在系统注入根证书（CA）**。
  - **规则开关可持久化**：加速规则的启用/禁用状态写入本地存储，重启应用后自动恢复。
  - **双向流量速率监控**：实时测量内核与本地代理的上行/下行实时吞吐速率。

- 🛡️ **Steam 令牌 (Steam Guard)**
  - **RFC-6238 HMAC-SHA1 算法**：严密对齐 Steam 官方认证算法。
  - **Valve 官方时钟毫秒级同步**：实时校准本地系统与 Valve 官方服务器的时钟偏差。
  - **多账号管理与一键复制**：支持 `.maFile` 一键导入、多账号快速切换与单点清理。
  - **密钥本地加密**：`shared_secret` 使用 Android Keystore 的 AES-256-GCM 加密落盘，旧版本明文数据自动迁移。

- 📡 **网络连通性雷达**
  - 实时探测四大反向代理节点与官方 API（Steam 社区、Pixiv、Steam API、GitHub）的网络往返延迟与可用性。

- 🚀 **16KB Page Size 原生内存对齐**
  - C 原生 `libtun2socks.so` 针对 `arm64-v8a`、`armeabi-v7a`、`x86_64` 平台执行 `max-page-size=0x4000` 编译，原生兼容 Android 15 及更高版本，杜绝 ELF 对齐崩溃。

---

## 🛠️ 构建与编译

### 编译依赖
- Android SDK 35 (API 35, Build Tools 35.0.0)
- JDK 17+ (推荐 Eclipse Temurin 或 Azul Zulu JDK 21)
- NDK 28.0+（仅修改 `libtun2socks.so` 原生源码时需要）

> 项目自带标准 Gradle Wrapper（8.9），克隆后无需安装 Gradle，`./gradlew` 会自动下载对应发行版。

### 签名（可选）

Release 默认回退使用 debug 签名。如需自定义签名，请设置以下环境变量：

```bash
export WATTKIT_STORE_FILE=/path/to/release.keystore
export WATTKIT_STORE_PASSWORD=******
export WATTKIT_KEY_ALIAS=your-alias
export WATTKIT_KEY_PASSWORD=******
```

### 本地构建 Release APK
```bash
# 进入工程目录
cd WattKit

# 使用 Gradle Wrapper 执行 Release 打包（Windows 使用 gradlew.bat）
./gradlew assembleRelease
```
编译产物位于 `app/build/outputs/apk/release/app-release.apk`。

---

## 📝 更新日志

### v1.0.1
**SNI 解析器加固**
- 用严格边界校验的解析器替换原 ByteBuffer 实现：畸形/不完整的 TLS record 直接拒绝，校验 SNI 主机名字符集与 label 长度，拒绝重复 `host_name` 与超长扩展列表
- 新增 `SniSnifferTest`（JUnit 4，9 个用例）与 GitHub Actions 工作流（`testDebugUnitTest` + `assembleDebug`）

**构建与打包**
- 用标准 Gradle 8.9 Wrapper 替换原先硬编码本机路径的 `gradlew.bat`，克隆后开箱即可构建
- Release 签名不再硬编码作者私有 keystore：未设置 `WATTKIT_*` 环境变量时回退 debug 签名
- 补齐缺失的 `proguard-rules.pro`；`compileSdk` / `targetSdk` 提升至 35，AGP 升至 8.7.3

**加速规则**
- 清理含 URL 路径的死规则（如 `www.google.com/recaptcha`）——SNI 只见主机名，此类规则永不命中；改为整域重定向至 `www.recaptcha.net`
- 规则开关状态持久化，重启后不再丢失
- 规则列表加锁 + 快照，消除 UI 线程与代理 IO 线程的并发访问风险

**本地代理**
- 按 TLS record 长度循环读满 ClientHello（上限约 16KB），修复分段或超大握手包导致 SNI 嗅探失败
- 校验 SOCKS5 请求版本号，失败返回标准应答而非静默挂起
- UDP ASSOCIATE 由「虚假端口应答」改为明确拒绝

**安全与稳定性**
- `shared_secret` 改用 Android Keystore AES-256-GCM 加密存储，旧明文自动迁移
- 关闭 adb / 云备份应用数据
- 补充 `VpnService.onRevoke()`，权限被撤销时完整停止服务
- tun2socks 日志级别由 debug 降为 warning

### v1.0.0
- 首个正式版本：纯 Kotlin + Jetpack Compose 原生重构、iOS 26 Cupertino 浅色视觉、Liquid Glass 悬浮 Dock、16KB ELF 对齐、22 项加速规则、Steam Guard 令牌与连通性雷达

---

## 📄 开源许可

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源协议。
致敬原版 Steam++ / Watt Toolkit 开源生态。
