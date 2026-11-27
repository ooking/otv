# APK 签名与 GitHub Actions 发布配置指南

Android 系统要求覆盖安装（`adb install -r` 或应用版本升级）的新旧 APK 必须使用**完全相同的数字签名证书**，否则会报错 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

若未配置固定签名：
- 本机执行 `assembleDebug` 使用的是当前电脑随机生成的 `~/.android/debug.keystore`；
- GitHub Actions 在未配置密钥时，每次运行都会在云端临时虚拟机上随机生成一把一次性 `debug.keystore`。

这会导致本机包与云端包无法互相覆盖安装，甚至 **GitHub Releases 前后两次发布的版本之间也无法直接覆盖升级**（用户必须先卸载旧版，导致频道排序、隐藏列表、收藏夹和节目表缓存全部清空）。

按照以下步骤生成一把固定签名证书并配置到 GitHub Actions，即可一劳永逸地统一本机与云端发布签名。

---

## 一、 在本机生成专属签名证书（只需执行一次）

推荐直接运行项目提供的交互式初始化脚本，它会一步完成证书创建、生成本地私密配置文件 `keystore.properties`（已被 `.gitignore` 忽略、权限设为 `600`），并自动将 Base64 编码复制到剪贴板：

```sh
./shell/init-keystore.sh
```

如果你想手动使用 `keytool` 生成证书，也可以在终端执行（将 `<你的密码>` 替换为你自己设定的强密码）：

```sh
mkdir -p ~/.android
keytool -genkeypair -v \
  -keystore ~/.android/otv-release.keystore \
  -alias otv \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "<你的密码>" \
  -keypass "<你的密码>" \
  -dname "CN=OTV, O=XiaomiTV, C=CN"
```

参数说明：
- `-keystore`：生成的证书文件路径（`~/.android/otv-release.keystore`）。
- `-alias`：密钥别名（此处设为 `otv`）。
- `-validity`：证书有效天数（`10000` 天，约 27 年）。
- `-storepass` / `-keypass`：证书库密码与密钥密码（建议设为一致，便于管理）。

> **重要提示**：请务必妥善备份 `~/.android/otv-release.keystore` 文件与密码。一旦丢失，后续发布的版本将无法覆盖升级旧版本。

如需核对已生成证书的 SHA-256 指纹信息，可执行：

```sh
keytool -list -v -keystore ~/.android/otv-release.keystore -alias otv
```

---

## 二、 将证书编码为单行 Base64 字符串

GitHub Secrets 仅支持存储纯文本，需要将二进制 `.keystore` 文件转换为无换行的 Base64 字符串：

- **macOS（直接复制到剪贴板）**：
  ```sh
  base64 -i ~/.android/otv-release.keystore | tr -d '\n' | pbcopy
  ```
  执行完成后，完整的 Base64 文本已自动复制到系统剪贴板。

- **Linux（输出到终端）**：
  ```sh
  base64 -w 0 ~/.android/otv-release.keystore ; echo
  ```

---

## 三、 在 GitHub 仓库配置 Actions Secrets

仓库内的发布工作流 [`.github/workflows/release.yml`](../.github/workflows/release.yml) 已内置正式签名逻辑，只需在仓库后台填入对应的 4 个 Secret：

1. 打开 GitHub 仓库主页，点击顶部 **Settings**。
2. 在左侧菜单展开 **Secrets and variables** → 点击 **Actions**。
3. 在 **Repository secrets** 区域点击 **New repository secret**，依次添加以下 4 项：

| Secret 名称 | 填写内容 | 说明 |
| :--- | :--- | :--- |
| `KEYSTORE_BASE64` | 粘贴第二步生成的 Base64 字符串 | 签名证书文件的 Base64 编码内容 |
| `KEYSTORE_PASSWORD` | 第一步设定的 `<你的密码>` | 对应 `-storepass` 证书库密码 |
| `KEY_ALIAS` | `otv` | 对应 `-alias` 密钥别名 |
| `KEY_PASSWORD` | 第一步设定的 `<你的密码>` | 对应 `-keypass` 密钥密码 |

---

## 四、 触发 GitHub Actions 自动构建与发版

1. 进入 GitHub 仓库顶部的 **Actions** 标签页。
2. 在左侧选择 **发布 APK 到 Releases** 工作流。
3. 点击右侧 **Run workflow**：
   - **发布版本标签**：可留空（默认自动读取 [`app/build.gradle`](../app/build.gradle) 中的 `versionName` 并加上 `v` 前缀，如 `v1.0.0`），也可手动指定如 `v1.0.1`。
   - **可选补充说明 (`changelog`)**：留空时会自动提取距离上一版本标签的所有 Git 提交记录并按「新增功能 / 体验优化 / 问题修复 / 其他更新」分类生成中文更新日志；若填写说明文字，则会作为「版本说明」置顶显示在自动生成的日志上方。
   - **是否标记为预发布版本**：按需勾选。
4. 工作流检测到上述 Secret 已配置后，会自动执行 `./gradlew assembleRelease`，完成 `zipalign` 字节对齐与 `apksigner` 正式签名，并将 `OTV-vX.Y.Z.apk` 上传至 GitHub Releases。

---

## 五、 本机快捷构建 Debug 与 Release（与线上版本无缝互通）

项目已支持通过根目录私密文件 `keystore.properties`（参考模板 [`keystore.properties.example`](../keystore.properties.example)，已加入 `.gitignore` 绝不上传）自动为 Release 包签名，同时将 Debug 包配置为独立包名 `com.xiaomitv.dtmb.debug`（桌面名称显示为 `OTV Debug`），实现正式版与调试版在电视上独立共存。

日常构建只需使用 `shell/` 目录下的两个脚本：

```sh
# 1. 构建 Debug 包（包名 com.xiaomitv.dtmb.debug，与正式版共存）
./shell/build-debug.sh
./shell/build-debug.sh -i          # 构建后自动 adb 安装并启动

# 2. 构建并自动签名 Release 包（读取 keystore.properties，与 GitHub Releases 签名一致）
./shell/build-release.sh
./shell/build-release.sh -i        # 构建后自动 adb 覆盖安装并启动

# 3. 通过无线 ADB 连接电视并覆盖安装指定版本（首次传入电视 IP 后会自动记住）
./shell/install-tv.sh release 192.168.31.88
./shell/install-tv.sh release       # 后续免输 IP 直接重连并覆盖安装正式版
./shell/install-tv.sh debug -b      # 重新编译并安装调试共存版
./shell/install-tv.sh release -f    # 若电视上旧包签名不一致，自动卸载旧包再重装
```

构建完成后，带版本号命名的安装包会自动归档至 `dist/` 目录（如 `dist/OTV-v1.0.0-release.apk` 和 `dist/OTV-v1.0.0-debug.apk`）。

### 附：首次切换签名时的过渡处理
如果电视上当前安装的是旧的随机调试签名包，首次换用新正式签名安装时需先卸载一次旧包，之后的所有升级均可直接使用 `adb install -r` 覆盖安装：

```sh
adb -s <电视序列号> uninstall com.xiaomitv.dtmb
adb -s <电视序列号> install app/build/outputs/apk/release/app-release.apk
```
