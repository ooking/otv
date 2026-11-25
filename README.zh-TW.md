# OTV

[简体中文](README.md) | 繁體中文 | [English](README.en.md)

專為小米 Android TV 打造的現代化地面數碼電視（DTMB）播放器。

## 為什麼做 OTV？

小米電視自帶的 DTMB 地面數碼電視應用過於簡陋——介面陳舊、缺少直觀的節目表、無法自由排序或隱藏頻道，切換音軌和字幕也很繁瑣。

OTV 基於 Android TV 原生 TIF 架構，直接調用電視內置調諧器，提供更現代、順手的睇電視體驗：

- **沉浸式頻道欄**：橫向卡片預覽各頻道正在播出的節目與進度，轉台更直觀。
- **矩陣節目指南 (EPG)**：時間軸 + 多頻道網格視圖，支援本地快取，亦可匯入 XMLTV 節目源補齊缺失的節目表。
- **頻道管理與排序**：支援自訂頻道順序、收藏常用頻道、隱藏不想看的頻道。
- **便捷影音控制**：快速切換音軌／聲道與廣播字幕；內置「小米音訊相容」模式，解決部分頻道有畫無聲的問題。

## 介面預覽

| 底部頻道欄 | 矩陣節目指南 (EPG) |
| :---: | :---: |
| ![底部頻道欄](docs/screenshots/channel-bar.jpg) | ![矩陣節目指南](docs/screenshots/epg-guide.jpg) |
| **頻道管理與排序** | **設定與選項** |
| ![頻道管理與排序](docs/screenshots/channel-manager.jpg) | ![設定與選項](docs/screenshots/settings-menu.jpg) |

## 快速開始

### 1. 首次設定
1. 開啟 OTV，依系統視窗提示授予「電視節目」權限。
2. 若電視尚未搜台，應用會自動提示並可一鍵跳轉至小米電視自帶「地面數碼電視」完成搜台，按返回鍵回到 OTV 即可自動載入頻道並播放。

### 2. 遙控器操作

| 按鍵 | 功能 |
| :--- | :--- |
| **上 / 下** | 切換頻道，頂部顯示目前及下一檔節目資訊條 |
| **確認 / 下** | 開啟底部沉浸式頻道欄（左右瀏覽頻道，向上切換篩選、EPG 與設定） |
| **右 / Guide** | 開啟矩陣節目指南 (EPG)，瀏覽各時段節目 |
| **左** | 查看目前節目簡介（有詳細簡介時再按一次展開全文） |
| **Menu（選單）** | 開啟右側設定與選項選單（音軌、字幕、畫質／聲音、收藏與隱藏） |

### 3. 實用提示
- **有畫面無聲音**：「小米音訊相容」在小米／Redmi 電視上已預設開啟；若無聲音請先檢查電視媒體音量，或進入「設定選項 → 音訊診斷」排查。
- **節目表不全**：部分廣播訊號僅提供少量節目資訊，可在節目指南 (EPG) 空白時段按確認鍵，或在「設定選項 → 匯入 XMLTV 節目表」中一鍵下載香港／HOY 節目源，亦可匯入自訂 `.xml` / `.xml.gz` 節目源。

## 建置與開發

需要 JDK 17+、Android SDK Platform 35 與 Build-Tools 34.0.0。專案於 `shell/` 目錄下提供開箱即用的建置腳本，歸檔產物統一輸出至 `dist/` 目錄：

### 1. 建置 Debug APK（獨立套件名 `com.xiaomitv.dtmb.debug`，可與正式版共存）

```sh
./shell/build-debug.sh
# 加上 -i 可於建置完成後自動透過 adb 安裝至電視並啟動：
./shell/build-debug.sh -i
```

### 2. 建置 Release APK（正式套件名 `com.xiaomitv.dtmb`）

首次建置正式版前，執行一次初始化腳本產生簽章憑證與本機私密設定 `keystore.properties`（已被 Git 忽略，不會上傳）：

```sh
# 僅首次執行一次：產生憑證並寫入本機 keystore.properties
./shell/init-keystore.sh

# 一鍵建置並簽章正式版（加上 -i 可自動覆蓋安裝至電視並啟動）
./shell/build-release.sh
./shell/build-release.sh -i
```

### 3. 透過 ADB 連線電視並覆蓋安裝

```sh
# 首次指定電視區域網路 IP（自動記住位址至本機 .tv-device，後續可省略 IP）
./shell/install-tv.sh release 192.168.31.88
./shell/install-tv.sh debug 192.168.31.88

# 後續一鍵重連電視並覆蓋安裝（加上 -b 先重新編譯，加上 -f 遇簽章衝突時自動解除安裝重裝）
./shell/install-tv.sh release
./shell/install-tv.sh debug -b
```

更多架構說明、GitHub Actions 簽章設定與實機除錯指南，請參閱 [開發文件](docs/README.md) 與 [簽章發佈指南](docs/release-signing.md)。

## 開源授權

OTV 原創程式碼採用 [MIT 授權條款](LICENSE)。
