# 模拟平板 · SimPad

[![Release](https://img.shields.io/github/v/release/rkdawn/SimPad)](https://github.com/rkdawn/SimPad/releases/latest)
[![Checks](https://github.com/rkdawn/SimPad/actions/workflows/check.yml/badge.svg)](https://github.com/rkdawn/SimPad/actions/workflows/check.yml)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**手机模拟平板，实现双手机设备同时登录。**

通过在目标应用中模拟平板身份，让另一台手机以平板设备身份登录。支持微信、钉钉和企业微信。

## 特点

- 目标应用内模拟平板身份，不修改手机的全局系统机型。
- 适配平板登录入口与多设备登录。
- 保留聊天输入区域的兼容处理。
- 缓存方法定位结果与安装包摘要，减少重复扫描和读取。

## 使用要求

- Android 8.1及以上。
- 支持 modern Xposed API 101 的模块框架。
- 目标应用：微信 `com.tencent.mm`、钉钉 `com.alibaba.android.rimet`、企业微信 `com.tencent.wework`。

## 安装

1. 从 [Releases](https://github.com/rkdawn/SimPad/releases/latest) 下载唯一正式安装包 **`SimPad-1.0.0.apk`**。
2. 安装模块，在框架中启用并选择目标应用作用域。
3. 结束目标应用后重新打开，使用其平板登录入口。

具体适配情况取决于应用版本及其多设备登录规则。不要同时启用其他修改平板身份的模块。

## 开发与许可

[构建说明](docs/BUILD.md) · [技术说明](docs/PERFORMANCE.md) · [版本规则](VERSIONING.md)

本项目采用 [GPL-3.0-only](LICENSE)。第三方组件许可与来源详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

感谢 [I-Am-Pad](https://github.com/Houvven/I-Am-Pad)、[ImPad](https://github.com/NKU100/ImPad)、[WeChatTablet](https://github.com/Xposed-Modules-Repo/top.hookvip.wxtablet)、[DexKit](https://github.com/LuckyPray/DexKit) 和 [libxposed](https://github.com/libxposed/api) 的公开工作。
