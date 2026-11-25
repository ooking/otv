#!/usr/bin/env bash
# 构建并签名 Release 版本 APK（读取根目录 keystore.properties，正式包名 com.xiaomitv.dtmb）
# 用法: ./shell/build-release.sh [-i|--install] [-s|--serial <电视序列号>]

set -euo pipefail

# shellcheck source=_common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

parse_build_args "build-release.sh" "$@"
setup_java_env

PROPS_FILE="${PROJECT_ROOT}/keystore.properties"
if [[ ! -f "$PROPS_FILE" ]]; then
    echo "错误: 未找到本地签名配置文件 keystore.properties" >&2
    echo "" >&2
    echo "请先执行以下任一方式完成初始化：" >&2
    echo "  方式 1 (推荐): 运行 ./shell/init-keystore.sh 交互式生成证书与配置文件" >&2
    echo "  方式 2       : 复制 keystore.properties.example 为 keystore.properties 并填入已有证书信息" >&2
    exit 1
fi

# 确保私密密码文件仅当前用户可读写
chmod 600 "$PROPS_FILE"

STORE_PATH="$(sed -n 's/^storeFile=//p' "$PROPS_FILE" | head -n 1 | xargs)"
if [[ "$STORE_PATH" == "~/"* ]]; then
    STORE_PATH="${HOME}/${STORE_PATH#"~/"}"
elif [[ "$STORE_PATH" != /* ]]; then
    STORE_PATH="${PROJECT_ROOT}/${STORE_PATH}"
fi

if [[ -z "$STORE_PATH" || ! -f "$STORE_PATH" ]]; then
    echo "错误: keystore.properties 中指定的证书文件不存在: ${STORE_PATH:-<空>}" >&2
    echo "请检查 keystore.properties 或重新运行 ./shell/init-keystore.sh" >&2
    exit 1
fi

cd "$PROJECT_ROOT"
GRADLE_ARGS=(":app:assembleRelease")
if [[ "$NEEDS_SKIP_LINT_VITAL" == "true" ]]; then
    GRADLE_ARGS+=("-x" "lintVitalRelease" "-x" "lintVitalAnalyzeRelease")
fi

echo "正在构建并签名 Release APK (包名: com.xiaomitv.dtmb)..."
./gradlew "${GRADLE_ARGS[@]}"

APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
    echo "错误: 未找到已签名的 Release 产物 (${APK_PATH})，请检查 keystore.properties 配置是否完整。" >&2
    exit 1
fi

archive_apk "$APK_PATH" "release"
install_if_requested "$APK_PATH" "com.xiaomitv.dtmb"
