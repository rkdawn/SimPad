# 来源与第三方组件声明

SimPad 采用 **GPL-3.0-only**，保留根目录 `LICENSE` 全文及APK中的许可资料。名称变化不抹去历史来源。

## 实现来源与致谢

感谢以下项目及维护者的公开工作。

| 项目 | 固定参考 | 当前借鉴范围 |
|---|---|---|
| [Houvven/I-Am-Pad](https://github.com/Houvven/I-Am-Pad) | `28f00bc448ac5545253f77092adee821524f5a3f`，GPL-3.0 | modern模块入口、启动前置时序及钉钉多端资格定位思路 |
| [NKU100/ImPad](https://github.com/NKU100/ImPad) | `17f2b083097461e55bdfd9e4108156425af20ac0`，GPL-3.0-only | 微信原流程／聊天例外、企微处理和批次禁用回滚；未引入完整兼容框架 |
| [WeChatTablet](https://github.com/Xposed-Modules-Repo/top.hookvip.wxtablet) | `92cc10e` 对应公开行为参考 | 登录入口、页面和聊天布局对照；没有复制未许可源码，未引入YukiHookAPI／FastKV |
| [DexKit](https://github.com/LuckyPray/DexKit) | 2.1.0 | DEX定位工具 |
| [libxposed](https://github.com/libxposed/api) | API101.0.1 | 框架编译接口 |
| Android／[libcore](https://github.com/LineageOS/android_libcore) | `fd478b8cfa76665d7683522a9fc7a0c962e9ff43` | 公共StackWalker接口及实现研究；没有打包Android源码 |

现役规则选择、代码身份、缓存职责、启动协调和测试为轻量维护实现。独立B Hook和模块主动共享缓存写入已取消；不将旧文档的内部缓存修正描述为现役业务。

当前安装身份与源码命名空间统一为 `io.github.rkdawn.simpad`。

## 实际运行依赖

| 坐标 | 许可 |
|---|---|
| `org.luckypray:dexkit:2.1.0` | Apache-2.0；C++ Core 为LGPL-3.0 |
| `com.google.flatbuffers:flatbuffers-java:23.5.26` | Apache-2.0 |
| `dev.rikka.ndk.thirdparty:cxx:1.2.0` | Apache-2.0；保留C++ runtime许可义务 |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.10` | Apache-2.0 |
| `org.jetbrains:annotations:13.0` | Apache-2.0 |

`io.github.libxposed:api:101.0.1` 为Apache-2.0，compileOnly，由框架提供而非打包到APK。JUnit、Android/Gradle工具仅为开发依赖。

完整GPL、Apache-2.0、LGPL文本及NOTICE在 `app/src/main/assets/licenses`，随APK提供。固定DexKit的源码与Maven发布入口：

- https://github.com/LuckyPray/DexKit/releases/tag/2.1.0
- https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.1.0/

本次没有升级运行库或新增依赖。分发不包含目标应用APK、私有日志、账号数据、签名私钥或编译进APK的测试fixture。正式源码在仓库和发布源码包中提供；公开发行只保留一个正式安装APK。
