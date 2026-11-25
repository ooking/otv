# OTV 开发文档

OTV 是面向小米 Android TV 的 DTMB 地面数字电视应用。电视自带直播应用负责搜台，OTV 通过 Android TV Input Framework（TIF）读取固件公开的频道和节目数据，使用 MediaTek 调谐器播放。除了直播，应用还提供收藏、节目指南、XMLTV 导入、音轨及广播字幕选择。

1. 按[快速开始](getting-started.md)配置环境、构建 APK，并在已搜台的开发电视上启动应用。
2. 查[架构与源码导航](architecture.md)定位改动，了解遥控器、播放和节目数据的调用关系。
3. 用[回归与排障](verification.md)拟定与改动对应的验证方案，获得同意后执行。
4. 按[APK 签名与 GitHub Actions 发布配置指南](release-signing.md)生成专属签名证书并配置云端与本地统一发布签名。

遥控器日常操作、节目源和既有真机验证记录见[项目 README](../README.md)。
