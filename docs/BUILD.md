# 构建

工具版本：JDK21、Android SDK／Build Tools36、NDK28.2.13676358、Gradle9.4.1、AGP9.1.0。

Linux/macOS示例：

```bash
mkdir -p .cache/gradle .cache/android-user tmp
export GRADLE_USER_HOME="$PWD/.cache/gradle"
export ANDROID_USER_HOME="$PWD/.cache/android-user"
export TEMP="$PWD/tmp" TMP="$PWD/tmp" TMPDIR="$PWD/tmp"
export SIMPAD_SIGNING_STORE="$PWD/.cache/android-user/debug.keystore"
./gradlew --no-daemon -Djava.io.tmpdir="$TMPDIR" :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

Windows使用PowerShell7，设置相同环境变量后运行 `gradlew.bat`。缓存和临时文件在当前项目内。官方安装包位于Releases；上面的debug产物仅用于开发，不是公开发行包。

官方发布签名不随源码分发。自行签名构建不能覆盖不同签名的模块。
