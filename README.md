# WattKit (Android)

> **Modern iOS Cupertino Style Steam Toolkit & Network Accelerator for Android**
> 基于纯 Kotlin + Jetpack Compose 从零重构，全面对齐 2026 年 Android 15+ 强制 16KB Page Size 原生内存标准。

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
  - **双向流量速率监控**：实时测量内核与本地代理的上行/下行实时吞吐速率。

- 🛡️ **Steam 令牌 (Steam Guard)**
  - **RFC-6238 HMAC-SHA1 算法**：严密对齐 Steam 官方认证算法。
  - **Valve 官方时钟毫秒级同步**：实时校准本地系统与 Valve 官方服务器的时钟偏差。
  - **多账号管理与一键复制**：支持 `.maFile` 一键导入、多账号快速切换与单点清理。

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

## 📄 开源许可

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源协议。
致敬原版 Steam++ / Watt Toolkit 开源生态。
