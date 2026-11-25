# OTV

简体中文 | [繁體中文](README.zh-TW.md) | [English](README.en.md)

专为小米 Android TV 打造的现代化地面数字电视（DTMB）播放器。

## 为什么做 OTV？

小米电视自带的 DTMB 地面数字电视应用过于简陋——界面陈旧、缺少直观的节目表、无法自由排序或隐藏频道，切换音轨和字幕也很繁琐。

OTV 基于 Android TV 原生 TIF 架构，直接调用电视内置调谐器，提供更现代、顺手的看电视体验：

- **沉浸式频道栏**：横向卡片预览各频道正在播出的节目与进度，切台更直观。
- **矩阵节目指南 (EPG)**：时间轴 + 多频道网格视图，支持本地缓存，也可导入 XMLTV 节目源补齐缺失的节目单。
- **频道管理与排序**：支持自定义频道顺序、收藏常用频道、隐藏不想看的频道。
- **便捷影音控制**：快速切换音轨/声道与广播字幕；内置「小米音频兼容」模式，解决部分频道有画无声的问题。

## 界面预览

| 底部频道栏 | 矩阵节目指南 (EPG) |
| :---: | :---: |
| ![底部频道栏](docs/screenshots/channel-bar.jpg) | ![矩阵节目指南](docs/screenshots/epg-guide.jpg) |
| **频道管理与排序** | **设置与选项** |
| ![频道管理与排序](docs/screenshots/channel-manager.jpg) | ![设置与选项](docs/screenshots/settings-menu.jpg) |

## 快速开始

### 1. 首次配置
1. 打开 OTV，根据系统弹窗提示授予「电视节目」权限。
2. 若电视尚未搜台，应用会自动提示并可一键跳转至小米电视自带「地面数字电视」完成搜台，按返回键回到 OTV 即可自动加载频道并播放。

### 2. 遥控器操作

| 按键 | 功能 |
| :--- | :--- |
| **上 / 下** | 切换频道，顶部显示当前及下一档节目信息条 |
| **确认 / 下** | 打开底部沉浸式频道栏（左右浏览频道，向上切换筛选、EPG 与设置） |
| **右 / Guide** | 打开矩阵节目指南 (EPG)，浏览各时段节目 |
| **左** | 查看当前节目简介（有详细简介时再按一次展开全文） |
| **Menu（菜单）** | 打开右侧设置与选项菜单（音轨、字幕、画质/声音、收藏与隐藏） |

### 3. 实用提示
- **有画面无声音**：「小米音频兼容」在小米 / Redmi 电视上已默认开启；若无声音请先检查电视媒体音量，或进入「设置选项 → 音频诊断」排查。
- **节目表不全**：部分广播信号仅提供少量节目信息，可在节目指南 (EPG) 空白时段按确认键，或在「设置选项 → 导入 XMLTV 节目表」中一键下载香港/HOY 节目源，也可导入自定义 `.xml` / `.xml.gz` 节目源。

## 构建与开发

需要 JDK 17+、Android SDK Platform 35 与 Build-Tools 34.0.0。项目在 `shell/` 目录下提供了开箱即用的构建脚本，归档产物统一输出至 `dist/` 目录：

### 1. 构建 Debug APK（独立包名 `com.xiaomitv.dtmb.debug`，可与正式版共存）

```sh
./shell/build-debug.sh
# 加 -i 可在构建完成后自动通过 adb 安装到电视并启动：
./shell/build-debug.sh -i
```

### 2. 构建 Release APK（正式包名 `com.xiaomitv.dtmb`）

首次构建正式版前，运行一次初始化脚本生成签名证书与本地私密配置 `keystore.properties`（已被 Git 忽略，不会上传）：

```sh
# 仅首次执行一次：生成证书并写入本地 keystore.properties
./shell/init-keystore.sh

# 一键构建并签名正式版（加 -i 可自动覆盖安装到电视并启动）
./shell/build-release.sh
./shell/build-release.sh -i
```

### 3. 通过 ADB 连接电视并覆盖安装

```sh
# 首次指定电视局域网 IP（自动记住地址至本地 .tv-device，后续可省略 IP）
./shell/install-tv.sh release 192.168.31.88
./shell/install-tv.sh debug 192.168.31.88

# 后续一键重连电视并覆盖安装（加 -b 先重新编译，加 -f 遇签名冲突时自动卸载重装）
./shell/install-tv.sh release
./shell/install-tv.sh debug -b
```

更多架构说明、GitHub Actions 签名配置与真机调试指南，请参阅 [开发文档](docs/README.md) 与 [签名发布指南](docs/release-signing.md)。

## 开源协议

OTV 原创代码采用 [MIT 许可证](LICENSE)。
