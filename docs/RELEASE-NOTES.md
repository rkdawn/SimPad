# SimPad 1.0.2 · Google Play 微信平板登录兼容修复

## 本地验证

- Google Play 微信 **8.0.80／3200**：修复微信结果缓存可能绕过内层判断 Hook 的问题，改 Hook 外层缓存包装方法 `vk.C()`。
- Google Play 微信 **8.0.79／3183**：保留原设备判断 Hook。
- 本机微信 8.0.80 新日志记录 Hook 已安装并进入 `LoginAsExDeviceUI`。完整登录、帐号保持及其它版本未据此宣称全部通过。
- 255 项 JVM 回归、39 项 APK 检查及 13 项 APK 验证器测试通过；Lint 0 错误、4 项既有警告。
- 应用包名与签名不变。安装后彻底退出微信再重新打开。
- 逐版本兼容范围见 `WECHAT-COMPATIBILITY.md`；新日志报告见 `../outputs/log-analysis/20261119-005327/report.md`。

## GitHub 发行附件

- APK：`SimPad-1.0.2.apk`
- 源码：`SimPad-1.0.2-source.zip`
- SHA-256：`SHA256SUMS.txt`

第三方组件致谢及许可见 `THIRD_PARTY_NOTICES.md`；本次发布不附加工具或协作者署名。测试通过不等同所有设备场景均已实机验收。
