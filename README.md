# OTV

面向小米 Android TV 的 DTMB 播放应用。它沿用 AOSP Live TV 的 TIF 播放架构，通过 `TvView` 调谐，使用 `TvInputManager` 发现输入，通过 `TvContract` 读取系统频道和节目表。项目使用 Java 和 Android SDK，APK 不包含本地库，适合在 Android 14 的 32 位 ARM 设备上验证。

新同事可从[开发文档](docs/README.md)开始，查看环境配置、源码导航和真机回归流程。

## 使用方式

1. 在电视自带的直播应用中选择地面数字电视并完成搜台。
2. 打开 OTV，在「选项 → 授予电视节目权限」中授权。应用会读取 MediaTek 调谐器的频道，优先显示标为 DTMB 的频道。固件没有这类标记时，显示同一调谐器的电视频道。
3. 全屏播放时按上、下切台，顶部弹出深色现代半透节目信息条（显示台号徽章、正在播和随后播出的节目，带播放进度指示）。按确认键或下键打开沉浸式底部频道控制栏，左右浏览横向频道走马灯预览正在播出的节目，按确认键切台，向上可切换至筛选、节目指南和选项胶囊。
4. 全屏按右或 Guide 打开图 3 风格经典矩阵节目指南（EPG）。顶部呈现选中节目的完整大看板与正在播放红标，左侧为纵向整点时间刻度，上方为横向频道表头，主体为随节目时长自适应的矩阵单元格。按上下键在当前频道内浏览时段，按左右键在相邻频道间快速切换，按确认键观看或查看详情，返回键退出指南。
5. 全屏按左键查看当前节目。有节目简介时，再按左键打开完整介绍。用 Menu 或频道栏的「选项」打开设置。通过「收藏当前频道」管理收藏，在频道栏的「全部 / 收藏」之间切换。应用会记住上次频道。「选项 → 声道与音轨」显示当前频道提供的音轨和所选项。遥控器有音轨键时，可以在播放中直接打开。独立的左、右声道输出由电视固件控制。
6. 如果小米电视的 DTMB 有画面但无声，可在「选项 → 小米音频兼容」开启适配。播放时仍然使用遥控器调整电视音量。
7. 在「选项 → 字幕」选择当前节目提供的广播字幕轨或关闭字幕。有字幕键的遥控器可以直接打开该菜单。应用记住每台频道的选择，轨道 ID 变化时可恢复唯一匹配的语言。

## 广播字幕与样式

MediaTek 的 TIF 会话一次选中一条字幕轨。如果频道把中英文做成同一条双语字幕轨，可以选择该轨显示两种语言。两条独立的字幕轨需要能同时解码和渲染两路字幕的播放接口。视频画面本身已有的字幕随节目播出。

字幕菜单的「系统字幕样式」打开电视的 Android 字幕偏好设置。该设置能影响采用 Android 字幕样式偏好的内容。DTMB 的字幕由调谐器绘制，颜色、字号及位置由节目和电视固件决定。 [Android TV 字幕接口](https://developer.android.com/training/tv/tif/ui)

频道和节目数据以固件向普通应用公开的记录为准。AOSP 的 `READ_TV_LISTINGS` 是运行时权限，跨应用查询通常只能读取可搜索记录。如果原生直播有频道而此处为空，需要检查固件的权限与频道可见性。选项中的「前往小米直播搜台」打开原生地面数字电视页面，按菜单键可选择搜台。返回 OTV 后会自动重新读取频道。

## 节目表缓存与补齐

应用在后台批量读取系统已有的节目，将未到期的记录保存到应用私有缓存。浏览频道时直接使用内存中已加载的节目。系统暂时只返回少量节目时，保留缓存中仍有效的后续节目。横向节目指南按实际时间绘制已有节目，用表头的亮色标记当前时间所在区间，未收到数据的时段显示「暂无节目资料」。

通过「选项 → 导入 XMLTV 节目表」可手动下载香港节目源或 HOY 补充节目源，也可以输入其他 HTTPS 节目源，或通过电视上的系统文件选择器选择 `.xml` 或 `.xml.gz`。XMLTV 的 `display-name` 需要与电视频道名称匹配，可忽略大小写、空格和标点，也兼容已知的港台电视及 CGTN 频道名称差异。同一时段优先显示系统节目资料，节目时间和标题一致时可用 XMLTV 的简介补齐。旧缓存保留已有节目，重新导入有简介的 XMLTV 即可补齐。XMLTV 时间应带有时区偏移，例如 `+0800`。应用将导入后的有效节目保存到本地，来源更新后可重新导入。 [香港节目源](https://epg.pw/xmltv.html?lang=en) · [HOY 补充节目源](https://github.com/litiande03/epg)

缓存只能保留已经拿到的节目。若系统仅公开当前及下一档，或某台没有记录，需要由固件补充广播节目，或导入有该频道的 XMLTV。节目表读取在独立线程运行，换台仍取决于调谐器锁定和解码时间。切换过程显示过渡界面，画面就绪后淡入。若目标频道调谐失败，应用尝试返回最近成功播放的频道。

2026 年 11 月 21 日在 MiTV-MFTR0 实测，系统单独提供的 7 天查询结果中，22 个频道有 6 个带节目，每台当次只有 1 条。导入两个节目源后，缓存一度覆盖 14 个频道。关闭应用进程再打开，HOY 76 仍可从本地看到 11 月 24 日凌晨的节目。节目源覆盖和节目期限会随时间变化。

## 构建与安装

使用 JDK 17、Android SDK Platform 35 和 Android SDK Build-Tools 34.0.0。通过 `ANDROID_HOME` 或 `local.properties` 指定 SDK 路径。

```sh
./gradlew :app:assembleDebug
adb -s <电视序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <电视序列号> shell am start -n com.xiaomitv.dtmb/.MainActivity
```

## 音频实现与验证

`Playback` 在前台申请媒体音频焦点，调谐、画面就绪及焦点变化时同步 TIF 会话音量。离开前台时释放会话与焦点。音频诊断显示焦点、收到的音轨及最近发送的音频请求，并可手动重试。实际声音由电视输出确认。

小米兼容默认关闭。开启后，`XiaomiTvAudio` 只在匹配的小米电视上处理当前的 MediaTek DTMB 会话。当画面就绪、应用持有音频焦点、节目允许播放且系统媒体音量开启时，通过公开的 `AudioManager.setParameters` 发送小米固件识别的 `TV-Mute=0`。切台、失去焦点或退出时，如果先前发过解除静音请求，应用发回 `TV-Mute=1`。该参数由小米固件实现。

在 MiTV-MFTR0（Android 14，32 位 ARM）上，标准 TIF 音量重试后 AudioFlinger 仍为 `MUTE`。在同一 AC3 频道开启兼容后，普通应用 UID 的请求进入音频 HAL，状态转为 `UNMUTE`，音频仍走电视输入至扬声器。现场确认 CGTN 纪录频道恢复出声。对照原生日志确认 `com.xiaomi.mitv.tvplayer` 通过 `TvViewManagerImpl.setVolumeIndex` 操作相同参数。

真机读取到 22 个频道，验证了节目表、遥控切台、收藏筛选和回到应用后恢复频道。切台和恢复播放后再次观测到 `UNMUTE`，退到后台时回到 `MUTE`。电视当前模式限制 Mochi 进入播放，因此这次音频对照以本应用在同一频道关闭、开启兼容的结果为准。

```sh
adb -s <电视序列号> logcat -d -s DtmbPlayback:I DtmbListings:I
adb -s <电视序列号> shell dumpsys tv_input
adb -s <电视序列号> shell dumpsys media.audio_flinger
adb -s <电视序列号> shell dumpsys audio
```

## 源码

- `MainActivity` 管理遥控器、权限、频道收藏和生命周期。
- `TvScreen` 管理直播浮层和面板导航，`ChannelSidebar` 管理频道侧栏，`GuideGrid` 和 `GuideTimeline` 管理横向节目指南。
- `TvListings` 管理系统数据查询。节目查询在独立线程执行，应用退到后台时取消。
- `ProgramGuide`、`GuideCache` 和 `XmlTvImporter` 管理节目表刷新、持久化与导入。
- `Playback` 管理 TIF 会话、音频焦点和诊断日志。
- `XiaomiTvAudio` 管理小米音频兼容开关及固件参数。

参考 [AOSP Live TV](https://android.googlesource.com/platform/packages/apps/TV/)、[Android 14 TvProvider](https://android.googlesource.com/platform/packages/providers/TvProvider/+/android-14.0.0_r1/src/com/android/providers/tv/TvProvider.java)、[TvView](https://developer.android.com/reference/android/media/tv/TvView) 与 [AudioManager.setParameters](https://developer.android.com/reference/android/media/AudioManager#setParameters(java.lang.String))。
