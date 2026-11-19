# 模拟平板 · SimPad

[![Release](https://img.shields.io/github/v/release/rkdawn/SimPad)](https://github.com/rkdawn/SimPad/releases/latest)
[![Checks](https://github.com/rkdawn/SimPad/actions/workflows/check.yml/badge.svg)](https://github.com/rkdawn/SimPad/actions/workflows/check.yml)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

手机模拟平板，实现双手机设备同时登录。支持微信、钉钉和企业微信，不修改系统全局机型。

## 微信适配

- Google Play 微信 **8.0.79／3183**。
- Google Play 微信 **8.0.80／3200**。

其他渠道和构建暂未验证。

## 使用要求

- Android 8.1及以上。
- 支持 modern Xposed API 101 的模块框架。

## 安装

1. 从 [Releases](https://github.com/rkdawn/SimPad/releases/latest) 下载 **SimPad-1.10.apk**。
2. 在模块框架中启用 SimPad，勾选目标应用作用域。
3. 彻底结束目标应用后重新打开，使用平板登录入口。

请勿同时启用其他修改平板身份的模块。

## 开发与许可

[构建说明](docs/BUILD.md) · [技术说明](docs/PERFORMANCE.md) · [集成参考](docs/INTEGRATION-SYNC.md)

本项目采用 [GPL-3.0-only](LICENSE)。第三方组件来源与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

感谢 [I-Am-Pad](https://github.com/Houvven/I-Am-Pad)、[ImPad](https://github.com/NKU100/ImPad)、[WeChatTablet](https://github.com/Xposed-Modules-Repo/top.hookvip.wxtablet)、[DexKit](https://github.com/LuckyPray/DexKit) 和 [libxposed](https://github.com/libxposed/api) 的公开工作。
