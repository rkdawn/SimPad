# 模拟平板 · SimPad

[![Checks](https://github.com/rkdawn/SimPad/actions/workflows/check.yml/badge.svg)](https://github.com/rkdawn/SimPad/actions/workflows/check.yml)
[![Release](https://img.shields.io/github/v/release/rkdawn/SimPad)](https://github.com/rkdawn/SimPad/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**在手机上为微信、钉钉、企业微信模拟平板身份的 Xposed 模块。**

SimPad 作用于所选应用的平板判断与登录适配路径，不修改整个手机的系统机型，也不是完整平板系统模拟器。

## 下载与使用

从 [Releases](https://github.com/rkdawn/SimPad/releases/latest) 下载 **`SimPad-1.0.0-release.apk`**。

1. 安装模块，在支持 **modern Xposed API 101** 的框架中启用。
2. 选择需要适配的应用作用域。
3. 结束目标应用后重新打开，按应用原有登录流程使用。

不要为了切换模块版本清除微信数据或反复扫码。框架、应用版本和账号侧限制仍会影响适配；本项目不保证所有版本、所有机型或所有登录场景都可用。

## 支持范围

| 应用 | 标准包名 | 处理范围 |
|---|---|---|
| 微信 | `com.tencent.mm` | 主进程；启动前置、聊天例外的平板判断、登录入口 After 显示 |
| 钉钉 | `com.alibaba.android.rimet` | 主进程；多端平板资格判断 |
| 企业微信 | `com.tencent.wework` | 主进程与服务子进程；设备类型与平板判断 |

Android 最低 API 27。新的聊天栈接口仅在 API 34 及以上使用；旧系统及普通接口失败时自动采用原线程栈判断。

当前明确的微信维护配置是 Play **8.0.79／3183**。其他版本仍需经过方法定位、签名与关联校验；不能把“理论定位规则”当作已实测覆盖。

## 实现特点

- 保留有效的启动前置窗口和原方法调用，避免以异步安装或 After 替代已验证时序。
- 微信采用 **一个启动 Hook＋两个业务 Hook**。聊天输入包保持例外；取消独立 B Hook 和模块主动共享结果缓存写入。
- 复用未变化、不可写安装 APK 的内容摘要，减少重开时重复读取大型安装集。
- 模块升级仍重验宿主内容；内容及规则不变时可复用定位计划。
- API34+ 通过共享 `StackWalker` 按批读取栈帧类名，命中聊天包后停止后续遍历；不缓存每次聊天结果。
- 输入或加载器变化会使方案退役；原方法异常、一次执行和批次安装／回滚合同保留。

C 仍是应用共享设备判断，这不是“所有非登录路径都完全不受影响”的保证。聊天保护也不等于覆盖所有聊天页面；作用范围见源码和实测说明。

## 实际验证与限制

这条实现由已实机使用的内部维护线正式化。维护者明确反馈：冷启动后的打开体验特别快，并批准按当前实现发布。

正式化前候选的两次运行记录：首轮模块准备 **1585ms**；后续命中缓存 **11ms**，无需再次读取约431MB安装集。两轮前置、输入复核和主界面创建通过。

这些是**模块阶段**数据，不是微信完整启动速度。系统主界面首次显示处于约0.4秒量级，但页面路径不同，不能直接计算新旧完整提速比例。日常聊天栈的手机收益没有独立持续计时；更多说明见 [性能说明](docs/PERFORMANCE.md)。

本地正式版回归与 APK 校验完成；云端检查结果以顶部 Checks 为准。连接告警并非本版已解决的问题：短时网络失败与长连接恢复记录仍可能出现，不篡改请求结果或隐藏提示。

## 原聊天判断回退

`SimPad-1.0.0-fallback.apk` 采用相同三点业务和缓存优化，但恢复完整 Thread 栈判断。需要隔离新的聊天栈策略时使用；它不是恢复旧三角色或主动缓存写入。

正式版 `versionName=1.0.0`；回退标识为 `1.0.0+legacy-chat`。两包沿用安装包名和维护签名，内部 `versionCode=32`，可覆盖切换。

显示名与项目名已改为 SimPad，但历史安装身份 `io.github.rkdawn.padbridge`、源码入口 `com.houvven.impad` 和缓存键保留，以维护覆盖安装和既有配置。不会因更名生成另一个模块。

## 构建

需要 JDK21、Android SDK／Build Tools36、NDK28.2.13676358；Gradle9.4.1、AGP9.1.0。运行源码构建前，请自行安装这些工具。

Linux/macOS 示例：

```bash
mkdir -p .cache/gradle .cache/android-user tmp
export GRADLE_USER_HOME="$PWD/.cache/gradle"
export ANDROID_USER_HOME="$PWD/.cache/android-user"
export TEMP="$PWD/tmp" TMP="$PWD/tmp" TMPDIR="$PWD/tmp"
export IMPAD_SIGNING_STORE="$PWD/.cache/android-user/debug.keystore"
./gradlew --no-daemon -Djava.io.tmpdir="$TMPDIR" :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

Windows 请使用 PowerShell7，并设置对应环境变量后运行 `gradlew.bat`。官方发布签名不随源码分发；自行签名的包不能覆盖不同签名的维护包。构建和缓存只写在项目内，不使用全局临时目录。

## 版本规则

本项目第一版正式发布为 **1.0.0**。小型兼容修复／性能优化使用补丁版本，新兼容功能使用次版本，不兼容合同变更才升主版本。测试次数不直接变成次版本号；Android内部构建序号独立递增。详见 [VERSIONING.md](VERSIONING.md)。

## 致谢

感谢这些项目及维护者提供实现基础、工具和对照资料：

- [Houvven/I-Am-Pad](https://github.com/Houvven/I-Am-Pad)：modern 模块入口、启动时序与钉钉适配参考。
- [NKU100/ImPad](https://github.com/NKU100/ImPad)：微信聊天例外、企微处理及事务回滚思路。
- [WeChatTablet](https://github.com/Xposed-Modules-Repo/top.hookvip.wxtablet)：登录入口、布局和行为对照；未复制未许可源码。
- [DexKit](https://github.com/LuckyPray/DexKit)：DEX 方法定位工具。
- [libxposed](https://github.com/libxposed/api)：modern Xposed API。
- Android／[libcore](https://github.com/LineageOS/android_libcore)：公共栈接口及实现参考。

本项目采用 **GPL-3.0-only**，保留来源许可与版权资料。具体借鉴范围、固定参考提交和第三方运行库许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
