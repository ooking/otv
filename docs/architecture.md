# 架构与源码导航

应用从 [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 注册电视启动入口。Java 源码位于 [com.xiaomitv.dtmb](../app/src/main/java/com/xiaomitv/dtmb/)，界面由原生 Android View 在代码中搭建，应用名、主题和电视桌面图标资源在 [res](../app/src/main/res/)。

| 改动场景 | 从哪里读起 |
| --- | --- |
| 遥控按键、选项、权限、收藏、恢复上次频道 | `MainActivity` 的 `dispatchKeyEvent`、`optionItems`、`loadChannels` |
| 面板层级、返回路径、焦点恢复、界面样式 | `TvScreen`、`TvStyle`，频道栏看 `ChannelSidebar`，节目卡看 `ProgramOverlay` |
| 调谐、切台失败回退、音频焦点、音轨 | `Playback`，轨道显示名看 `TrackLabels` |
| 小米电视有画面无声、广播字幕恢复 | `XiaomiTvAudio`、`BroadcastSubtitles` |
| 发现频道、系统节目查询和频道匹配 | `TvListings` |
| 节目刷新、XMLTV 导入、SQLite 缓存 | `ProgramGuide`、`XmlTvImporter`、`GuideCache` |
| 节目指南布局、遥控导航、按时间查找节目 | `GuideGrid`、`GuideTimeline` |

## 频道与播放

`MainActivity` 通过 `TvInputManager` 查找 `TvListings.INPUT_ID` 定义的 MediaTek 输入。获得 `READ_TV_LISTINGS` 后，用独立执行器查询 `TvContract`。频道列表优先使用 DTMB 标记，固件缺少该标记时回退到该调谐器的电视频道。Provider 能公开多少频道取决于固件。

遥控器选台由 `MainActivity` 交给 `Playback`，后者通过 `TvView` 调谐并处理会话回调。切换中由 `TvScreen` 显示过渡，成功后恢复画面，调谐失败时尝试回到最近成功播放的频道。离开前台时，Activity 取消频道及系统节目查询、注销观察者和输入回调，并停止播放、释放音频焦点。偏好保存在私有 `live_tv` SharedPreferences 中。

## 节目指南

`ProgramGuide` 将系统节目查询、XMLTV 解析和缓存操作放在独立的单线程执行器上，通过主线程 Handler 发布节目快照。系统查询有前台轮询和变化通知触发，遥控器浏览从内存快照读取。SQLite 私有数据库 `programs.db` 分来源保存系统和 XMLTV 数据，当前 schema 版本为 2，已有 v1→v2 简介字段迁移。

指南加载的窗口覆盖当前及之后 7 天。合并时系统数据优先，节目时间与标题一致时可补齐简介。下次查询覆盖变少时，仍保留尚未过期、时间不重叠的缓存。XMLTV 通过归一化后的频道名称匹配，重名歧义会跳过。导入支持本地 XML 或 gzip 文件，以及直接返回内容的 HTTPS 地址。下载有超时且禁用自动重定向，解析限制解压后体积和有效节目数量。

## 改动时沿用的约定

- UI 复用 `TvStyle` 的字号、间距、颜色和焦点样式。新增面板时检查方向键、确认、返回及恢复焦点，尤其是遥控器无触控的场景。
- 频道查询、数据库和 XMLTV I/O 留在后台。涉及切台或刷新时，沿用取消机制和回调状态检查，避免前一个请求覆盖当前界面。
- 固件兼容逻辑放在对应模块。小米音频兼容默认关闭，并受设备、当前输入、音频焦点和播放状态约束。修改时同时检查申请解除静音和离开播放时恢复静音的路径。
- 变更缓存表结构时维护升级路径，覆盖已有缓存的安装。修改导入逻辑时保留 HTTPS、解析限制和日志中的地址保护。
