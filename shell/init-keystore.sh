#!/usr/bin/env bash
# 首次初始化 Release 签名证书与本地 keystore.properties 配置向导

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS_FILE="${PROJECT_ROOT}/keystore.properties"
DEFAULT_KS_PATH="${HOME}/.android/otv-release.keystore"
DEFAULT_ALIAS="otv"

echo "=== OTV Release 签名初始化向导 ==="
echo ""

read -r -p "证书保存路径 [默认: ${DEFAULT_KS_PATH}]: " INPUT_KS_PATH
KS_PATH="${INPUT_KS_PATH:-$DEFAULT_KS_PATH}"
# 展开开头的 ~/
if [[ "$KS_PATH" == "~/"* ]]; then
    KS_PATH="${HOME}/${KS_PATH#"~/"}"
fi

read -r -p "密钥别名 (keyAlias) [默认: ${DEFAULT_ALIAS}]: " INPUT_ALIAS
KEY_ALIAS="${INPUT_ALIAS:-$DEFAULT_ALIAS}"

if [[ -f "$KS_PATH" ]]; then
    echo ""
    echo "检测到证书文件已存在: ${KS_PATH}"
    echo "将直接使用现有证书生成本地 keystore.properties（不会覆盖原证书）。"
    read -r -s -p "请输入该证书的密码: " KS_PASS
    echo ""
    if [[ -z "$KS_PASS" ]]; then
        echo "错误: 密码不能为空。" >&2
        exit 1
    fi
    # 校验证书库密码、别名以及内层私钥密码是否一致可用
    if ! keytool -keypasswd -keystore "$KS_PATH" -alias "$KEY_ALIAS" \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -new "$KS_PASS" >/dev/null 2>&1; then
        echo "错误: 无法使用该密码解锁证书或私钥 '${KEY_ALIAS}'，请核对密码与别名后重试。" >&2
        exit 1
    fi
else
    echo ""
    echo "即将创建新证书（keytool 要求密码至少 6 位字符）："
    read -r -s -p "请设定新证书密码: " KS_PASS
    echo ""
    if [[ ${#KS_PASS} -lt 6 ]]; then
        echo "错误: 证书密码长度不能少于 6 位。" >&2
        exit 1
    fi
    read -r -s -p "请再次输入密码确认: " KS_PASS_CONFIRM
    echo ""
    if [[ "$KS_PASS" != "$KS_PASS_CONFIRM" ]]; then
        echo "错误: 两次输入的密码不一致。" >&2
        exit 1
    fi

    mkdir -p "$(dirname "$KS_PATH")"
    keytool -genkeypair -v \
        -keystore "$KS_PATH" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$KS_PASS" \
        -keypass "$KS_PASS" \
        -dname "CN=OTV, O=XiaomiTV, C=CN"
    chmod 600 "$KS_PATH"
    echo "已生成签名证书: ${KS_PATH}"
fi

# 写入项目根目录私密配置文件（已被 .gitignore 忽略）
cat > "$PROPS_FILE" <<EOF
# 本机私密签名配置（已被 .gitignore 忽略，请勿提交到 Git）
storeFile=${KS_PATH}
storePassword=${KS_PASS}
keyAlias=${KEY_ALIAS}
keyPassword=${KS_PASS}
EOF
chmod 600 "$PROPS_FILE"

echo ""
echo "已生成本地配置文件: ${PROPS_FILE} (权限 600)"

if command -v pbcopy >/dev/null 2>&1; then
    base64 -i "$KS_PATH" | tr -d '\n' | pbcopy
    echo "已将证书的 Base64 字符串自动复制到系统剪贴板！"
    echo "如需同步配置 GitHub Actions，可在仓库 Settings -> Secrets -> Actions 中填入："
    echo "  KEYSTORE_BASE64   = (直接粘贴剪贴板内容)"
    echo "  KEYSTORE_PASSWORD = (你刚才设定的密码)"
    echo "  KEY_ALIAS         = ${KEY_ALIAS}"
    echo "  KEY_PASSWORD      = (你刚才设定的密码)"
fi

echo ""
echo "现在可以直接运行 ./shell/build-release.sh 构建已签名的正式版 APK 了。"
