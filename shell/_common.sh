#!/usr/bin/env bash
# 内部公共构建辅助函数（供 build-debug.sh 与 build-release.sh 调用）

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEEDS_SKIP_LINT_VITAL="false"
INSTALL_AFTER_BUILD="false"
ADB_SERIAL=""

# 解析通用命令行参数（-i/--install 自动安装，-s/--serial 指定电视序列号）
parse_build_args() {
    local script_name="$1"
    shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -i|--install)
                INSTALL_AFTER_BUILD="true"
                shift
                ;;
            -s|--serial)
                if [[ $# -lt 2 ]]; then
                    echo "错误: $1 需要提供电视设备序列号" >&2
                    exit 1
                fi
                ADB_SERIAL="$2"
                shift 2
                ;;
            -h|--help)
                echo "用法: ./shell/${script_name} [-i|--install] [-s|--serial <电视序列号>]"
                echo "  -i, --install           构建完成后自动通过 adb 安装到电视并启动应用"
                echo "  -s, --serial <序列号>   多设备连接时指定目标电视序列号"
                exit 0
                ;;
            *)
                echo "未知参数: $1（使用 --help 查看用法）" >&2
                exit 1
                ;;
        esac
    done
}

# 智能配置 Java 环境：优先选用本机 JDK 17，若为高版本 JDK 则自动跳过 AGP Lint 兼容限制
setup_java_env() {
    if [[ -x "/usr/libexec/java_home" ]]; then
        local jdk17
        jdk17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
        if [[ -n "$jdk17" && -d "$jdk17" ]]; then
            export JAVA_HOME="$jdk17"
        fi
    fi

    local java_bin="java"
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        java_bin="${JAVA_HOME}/bin/java"
    fi

    local version_line major_ver
    version_line="$("$java_bin" -version 2>&1 | head -n 1)"
    major_ver="$(echo "$version_line" | sed -E 's/.*"([0-9]+).*/\1/')"
    if [[ "$major_ver" =~ ^[0-9]+$ ]] && (( major_ver > 20 )); then
        NEEDS_SKIP_LINT_VITAL="true"
    fi
}

# 读取 app/build.gradle 中的 versionName
read_app_version() {
    grep -E "versionName[[:space:]]+['\"]" "${PROJECT_ROOT}/app/build.gradle" \
        | head -n 1 \
        | sed -E "s/.*['\"]([^'\"]+)['\"].*/\1/"
}

# 将 APK 归档到根目录 dist/ 并打印体积与校验和
archive_apk() {
    local src_apk="$1"
    local variant="$2"
    local version
    version="$(read_app_version)"

    local dist_dir="${PROJECT_ROOT}/dist"
    mkdir -p "$dist_dir"
    local out_apk="${dist_dir}/OTV-v${version}-${variant}.apk"
    cp -f "$src_apk" "$out_apk"

    local size sha256
    size="$(du -h "$out_apk" | awk '{print $1}')"
    if command -v shasum >/dev/null 2>&1; then
        sha256="$(shasum -a 256 "$out_apk" | awk '{print $1}')"
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256="$(sha256sum "$out_apk" | awk '{print $1}')"
    else
        sha256="N/A"
    fi

    echo ""
    echo "=========================================="
    echo " 构建完成 (${variant})"
    echo "------------------------------------------"
    echo " 原始产物: ${src_apk#"${PROJECT_ROOT}/"}"
    echo " 归档产物: ${out_apk#"${PROJECT_ROOT}/"} (${size})"
    echo " SHA-256 : ${sha256}"
    echo "=========================================="
}

# 查找可用 adb 路径
resolve_adb() {
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi
    local sdk_dir="${ANDROID_HOME:-}"
    if [[ -z "$sdk_dir" && -f "${PROJECT_ROOT}/local.properties" ]]; then
        sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${PROJECT_ROOT}/local.properties")"
    fi
    if [[ -n "$sdk_dir" && -x "${sdk_dir}/platform-tools/adb" ]]; then
        echo "${sdk_dir}/platform-tools/adb"
        return
    fi
    echo "错误: 未找到 adb 命令，请安装 Android Platform-Tools 或将其加入 PATH" >&2
    exit 1
}

# 可选自动安装并拉起主界面
install_if_requested() {
    local apk_path="$1"
    local app_id="$2"

    if [[ "$INSTALL_AFTER_BUILD" != "true" ]]; then
        return
    fi

    local adb_bin
    adb_bin="$(resolve_adb)"
    local adb_args=()
    if [[ -n "$ADB_SERIAL" ]]; then
        adb_args=(-s "$ADB_SERIAL")
    fi

    echo ""
    echo "正在安装到电视 (${app_id})..."
    "$adb_bin" "${adb_args[@]}" install -r "$apk_path"
    echo "正在启动应用..."
    "$adb_bin" "${adb_args[@]}" shell am start -n "${app_id}/com.xiaomitv.dtmb.MainActivity"
}
