#!/bin/sh
set -e

# ==============================================================================
# Ru-Beacon Public IPv4 Leak Guard Script
# Detects hardcoded public IPv4 addresses in git diff
# Excludes RFC 1918 private subnets, loopback, broadcast, and metadata IPs
# ==============================================================================

DIFF_TARGET="$1"
if [ -z "$DIFF_TARGET" ]; then
    DIFF_TARGET="--cached"
fi

if [ "$ALLOW_PUBLIC_IP" = "true" ]; then
    echo "[LEAK-GUARD] ALLOW_PUBLIC_IP=true 설정으로 공용 IP 검사를 건너뜁니다."
    exit 0
fi

echo "[LEAK-GUARD] Git diff 범위 검사 중: $DIFF_TARGET"

if [ "$DIFF_TARGET" = "-" ]; then
    DIFF_OUTPUT=$(cat)
else
    DIFF_OUTPUT=$(git diff $DIFF_TARGET -U0 2>/dev/null || true)
fi

if [ -z "$DIFF_OUTPUT" ]; then
    echo "[LEAK-GUARD] 검사할 diff 변경 사항이 없습니다. (통과)"
    exit 0
fi

LEAKED_IPS=$(echo "$DIFF_OUTPUT" \
    | grep -E "^\+[^\+]" \
    | grep -oE "\b([0-9]{1,3}\.){3}[0-9]{1,3}\b" \
    | grep -vE "^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.|127\.|169\.254\.|0\.0\.0\.0|255\.255\.255\.255)" \
    | grep -vE "^(173\.245\.|103\.(21|22|31)\.|141\.101\.|108\.162\.|190\.93\.|188\.114\.|197\.234\.|198\.41\.|162\.(158|159)\.|104\.(1[6-9]|2[0-7])\.|172\.(6[4-9]|7[01])\.|131\.0\.)" \
    | sort -u || true)

if [ -n "$LEAKED_IPS" ]; then
    echo ""
    echo "================================================================================"
    echo "[SECURITY REJECTED] 공용 IP(Public IPv4) 하드코딩 누출 감지!"
    echo "--------------------------------------------------------------------------------"
    echo "아래의 공용 IPv4 주소가 diff에 감지되었습니다:"
    echo "$LEAKED_IPS"
    echo ""
    echo "Ru-Beacon 보안 정책: Origin IP 은닉 및 보안 유지를 위해 실제 공용 IP 커밋을 금지합니다."
    echo "환경 변수, 도메인(nip.io/DNS), 또는 가상 표기(예: <NODE_IP>)를 사용하십시오."
    echo "================================================================================"
    echo ""
    exit 1
fi

echo "[LEAK-GUARD] 공용 IP 누출 없음 (통과)"
exit 0
