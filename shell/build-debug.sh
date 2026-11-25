#!/usr/bin/env bash
# 构建 Debug 版本 APK（独立包名 com.xiaomitv.dtmb.debug，可与正式版共存）
# 用法: ./shell/build-debug.sh [-i|--install] [-s|--serial <电视序列号>]

set -euo pipefail

# shellcheck source=_common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

parse_build_args "build-debug.sh" "$@"
setup_java_env

cd "$PROJECT_ROOT"
echo "正在构建 Debug APK (包名: com.xiaomitv.dtmb.debug)..."
./gradlew :app:assembleDebug

APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
archive_apk "$APK_PATH" "debug"
install_if_requested "$APK_PATH" "com.xiaomitv.dtmb.debug"
