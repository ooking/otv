# 快速开始

## 开发环境

| 项目 | 当前配置 |
| --- | --- |
| 工程 | Gradle 根工程 `XiaomiDtmb`，单一应用模块 `:app` |
| 语言与运行环境 | Java 17，构建使用 JDK 17 |
| 构建工具 | 仓库内的 Gradle Wrapper 8.7，Android Gradle Plugin 8.6.1 |
| Android SDK | Platform 35、Build-Tools 34.0.0，另装 Platform-Tools 获取 `adb` |
| 应用 | OTV，包名 `com.xiaomitv.dtmb`，最低 API 26，目标 API 34 |
| 真机 | 已有记录使用 MiTV-MFTR0（Android 14、32 位 ARM），其他机型取决于调谐器和固件 |

以仓库根目录导入 Android Studio，将 Gradle JDK 设为 17。命令行构建时用 `JAVA_HOME` 选择 JDK 17，用 `ANDROID_HOME` 指向 Android SDK，或在根目录创建 `local.properties` 并填写 `sdk.dir=/你的/Android/sdk`。本地 SDK 路径文件已被 Git 忽略。首次同步或构建需要访问 Gradle 和 Maven 仓库。

版本配置见 [app/build.gradle](../app/build.gradle) 和 [Gradle Wrapper 配置](../gradle/wrapper/gradle-wrapper.properties)。

## 构建 Debug APK

在项目根目录执行。Windows 上使用 `gradlew.bat :app:assembleDebug`。

```sh
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。应用通过系统 TIF 播放，APK 没有自带本地库。普通模拟器可用于查看部分界面，但直播、广播字幕和固件音频行为需要具备对应调谐器与信号的电视。

## 在开发电视上运行

真机操作前，先说明本次验证方案并获得同意。使用已有 ADB 授权的开发电视，在 `adb devices -l` 中确认设备，把下列 `TV_SERIAL` 替换成其实际序列号。

```sh
adb devices -l
adb -s TV_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TV_SERIAL shell am start -n com.xiaomitv.dtmb/.MainActivity
```

1. 打开 OTV，按系统弹窗提示授予电视节目权限。曾勾选不再询问时，可通过底部空状态按钮或「选项」直达系统应用详情页开启权限。
2. 若尚未搜台，按提示跳转至电视自带地面数字电视完成搜台，返回 OTV 后会自动加载频道并播放。
3. 全屏按上、下切台，确认键打开频道栏，右键或 Guide 打开节目指南。用返回键退出当前面板。
4. 若安装提示签名冲突，先核对设备上的安装包来源和签名。卸载会删除应用私有数据，包括收藏、字幕偏好和节目缓存。

改动前可按[架构与源码导航](architecture.md)找到入口。验证预期和日志命令见[回归与排障](verification.md)。
