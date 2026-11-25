#!/usr/bin/env bash
# 通过 ADB 连接小米电视并覆盖安装/启动 OTV（支持 debug 与 release）
# 用法: ./shell/install-tv.sh <debug|release> [电视IP[:端口]] [-b|--build] [-f|--force]

set -euo pipefail

# shellcheck source=_common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

DEVICE_CACHE_FILE="${PROJECT_ROOT}/.tv-device"
VARIANT=""
TV_ADDR=""
FORCE_BUILD="false"
FORCE_REINSTALL="false"

print_usage() {
    cat <<EOF
用法: ./shell/install-tv.sh <debug|release> [电视IP[:端口]] [选项]

参数:
  debug | release       指定安装调试版 (OTV Debug) 或正式版 (OTV)
  电视IP[:端口]         可选，小米电视局域网 IP（未带端口默认补 :5555，首次输入后自动记住）

选项:
  -b, --build           安装前先重新编译对应版本的最新 APK
  -f, --force           遇到签名不一致 (INSTALL_FAILED_UPDATE_INCOMPATIBLE) 时自动卸载旧版再重装
  -h, --help            显示此帮助信息

示例:
  ./shell/install-tv.sh release 192.168.31.88     # 首次连接电视 IP 并覆盖安装正式版
  ./shell/install-tv.sh release                   # 复用上次记住的电视 IP 直接覆盖安装正式版
  ./shell/install-tv.sh debug -b                  # 重新编译 Debug 版并安装到电视
  ./shell/install-tv.sh release -f                # 签名冲突时卸载旧版并安装新正式版
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        debug|release)
            VARIANT="$1"
            shift
            ;;
        -b|--build)
            FORCE_BUILD="true"
            shift
            ;;
        -f|--force)
            FORCE_REINSTALL="true"
            shift
            ;;
        -h|--help)
            print_usage
            exit 0
            ;;
        -*)
            echo "未知选项: $1" >&2
            print_usage >&2
            exit 1
            ;;
        *)
            if [[ -z "$TV_ADDR" ]]; then
                TV_ADDR="$1"
                shift
            else
                echo "多余的参数: $1" >&2
                print_usage >&2
                exit 1
            fi
            ;;
    esac
done

if [[ -z "$VARIANT" ]]; then
    echo "错误: 请指定要安装的版本类型 (debug 或 release)" >&2
    echo "" >&2
    print_usage >&2
    exit 1
fi

# 1. 确定目标 APK 与包名，必要时自动构建
if [[ "$VARIANT" == "debug" ]]; then
    APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
    APP_ID="com.xiaomitv.dtmb.debug"
    BUILD_SCRIPT="${PROJECT_ROOT}/shell/build-debug.sh"
else
    APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/release/app-release.apk"
    APP_ID="com.xiaomitv.dtmb"
    BUILD_SCRIPT="${PROJECT_ROOT}/shell/build-release.sh"
fi

if [[ "$FORCE_BUILD" == "true" || ! -f "$APK_PATH" ]]; then
    if [[ ! -f "$APK_PATH" ]]; then
        echo "本地尚未生成 ${VARIANT} APK，正在自动执行构建..."
    fi
    "$BUILD_SCRIPT"
fi

# 2. 解析并连接电视 ADB 设备
ADB_BIN="$(resolve_adb)"

normalize_tv_addr() {
    local raw="$1"
    if [[ "$raw" != *:* ]]; then
        echo "${raw}:5555"
    else
        echo "$raw"
    fi
}

TARGET_DEVICE=""
if [[ -n "$TV_ADDR" ]]; then
    TARGET_DEVICE="$(normalize_tv_addr "$TV_ADDR")"
elif [[ -f "$DEVICE_CACHE_FILE" ]]; then
    CACHED_ADDR="$(head -n 1 "$DEVICE_CACHE_FILE" | xargs)"
    if [[ -n "$CACHED_ADDR" ]]; then
        TARGET_DEVICE="$(normalize_tv_addr "$CACHED_ADDR")"
        echo "使用已保存的电视地址: ${TARGET_DEVICE}"
    fi
fi

if [[ -z "$TARGET_DEVICE" ]]; then
    # 检查是否已有在线的 adb 设备
    ONLINE_DEVICES=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && ONLINE_DEVICES+=("$line")
    done < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')

    if [[ ${#ONLINE_DEVICES[@]} -eq 1 ]]; then
        TARGET_DEVICE="${ONLINE_DEVICES[0]}"
        echo "检测到当前已连接设备: ${TARGET_DEVICE}"
    else
        read -r -p "请输入小米电视 IP 地址 (例如 192.168.31.88): " INPUT_IP
        if [[ -z "$INPUT_IP" ]]; then
            echo "错误: 未提供电视 IP 地址。" >&2
            exit 1
        fi
        TARGET_DEVICE="$(normalize_tv_addr "$INPUT_IP")"
    fi
fi

# 若目标是网络地址 (IP:端口)，主动执行 adb connect 并记住地址
if [[ "$TARGET_DEVICE" == *:* ]]; then
    echo "正在通过 ADB 连接电视 ${TARGET_DEVICE} ..."
    "$ADB_BIN" connect "$TARGET_DEVICE" || true
    echo "$TARGET_DEVICE" > "$DEVICE_CACHE_FILE"
fi

# 校验设备连接状态
get_device_state() {
    "$ADB_BIN" devices | awk -v dev="$TARGET_DEVICE" 'NR>1 && $1==dev {print $2; exit}'
}

DEV_STATE="$(get_device_state)"
if [[ "$DEV_STATE" == "unauthorized" ]]; then
    echo ""
    echo "提示: 请在电视屏幕上勾选「始终允许使用这台计算机进行调试」并点击确定..."
    read -r -p "在电视上点击允许后，按回车键继续..." _
    "$ADB_BIN" connect "$TARGET_DEVICE" >/dev/null 2>&1 || true
    DEV_STATE="$(get_device_state)"
fi

if [[ "$DEV_STATE" != "device" ]]; then
    echo "错误: 无法连接到电视 (${TARGET_DEVICE}，当前状态: ${DEV_STATE:-未连接})。" >&2
    echo "请确认：" >&2
    echo "  1. 电视与电脑处于同一局域网，且 IP 地址正确；" >&2
    echo "  2. 电视已在「设置 -> 账号与安全 -> ADB 调试」中开启允许。" >&2
    exit 1
fi

# 3. 执行覆盖安装（遇到签名冲突时支持提示或 -f 自动重装）
echo "正在覆盖安装 ${VARIANT} 版本 (${APP_ID}) 到 ${TARGET_DEVICE} ..."
set +e
INSTALL_OUTPUT="$("$ADB_BIN" -s "$TARGET_DEVICE" install -r "$APK_PATH" 2>&1)"
INSTALL_CODE=$?
set -e

if [[ $INSTALL_CODE -ne 0 ]]; then
    if [[ "$INSTALL_OUTPUT" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
        if [[ "$FORCE_REINSTALL" == "true" ]]; then
            echo "检测到旧版签名不一致，正在按 -f 参数卸载旧版并重新安装..."
            "$ADB_BIN" -s "$TARGET_DEVICE" uninstall "$APP_ID" || true
            "$ADB_BIN" -s "$TARGET_DEVICE" install "$APK_PATH"
        else
            echo "$INSTALL_OUTPUT" >&2
            echo "" >&2
            echo "错误: 电视上已安装的旧版本签名与当前包不一致，无法直接覆盖。" >&2
            echo "如需卸载旧版并全新安装，请加上 -f 参数运行：" >&2
            echo "  ./shell/install-tv.sh ${VARIANT} -f" >&2
            exit 1
        fi
    else
        echo "$INSTALL_OUTPUT" >&2
        exit "$INSTALL_CODE"
    fi
else
    echo "$INSTALL_OUTPUT"
fi

# 4. 启动应用主界面
echo "正在电视上启动应用 (${APP_ID})..."
"$ADB_BIN" -s "$TARGET_DEVICE" shell am start -n "${APP_ID}/com.xiaomitv.dtmb.MainActivity"
echo "安装并启动成功！"
