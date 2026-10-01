#!/bin/bash
# Slack 유저 ID ↔ Jira displayName 매핑 등록 스크립트
#
# 사용법:
#   ./scripts/register-user-mapping.sh                    # 대화형 등록
#   ./scripts/register-user-mapping.sh U03L1TJ0EBB 김영현  # 직접 지정
#   ./scripts/register-user-mapping.sh --list              # 등록된 매핑 조회
#
# Slack 유저의 실명과 Jira assignee 이름이 다를 때 사용합니다.
# 예: Slack 실명 "Young Hyun Kim" → Jira assignee "김영현"

set -eo pipefail

BASE_URL="${SPRING_BASE_URL:-http://localhost:8080}"
API_URL="${BASE_URL}/api/user-mappings"

# v0.0.82: 사용자 매핑 API 는 관리자 전용 — .env 의 관리자 계정(DASHBOARD_USER/PASSWORD)으로 인증한다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${DASHBOARD_USER:-}" || -z "${DASHBOARD_PASSWORD:-}" ]] && [[ -f "${SCRIPT_DIR}/../.env" ]]; then
    DASHBOARD_USER="$(grep -E '^DASHBOARD_USER=' "${SCRIPT_DIR}/../.env" | cut -d= -f2-)"
    DASHBOARD_PASSWORD="$(grep -E '^DASHBOARD_PASSWORD=' "${SCRIPT_DIR}/../.env" | cut -d= -f2-)"
fi
if [[ -z "${DASHBOARD_USER:-}" || -z "${DASHBOARD_PASSWORD:-}" ]]; then
    echo "관리자 계정이 필요합니다: DASHBOARD_USER / DASHBOARD_PASSWORD 환경변수 또는 .env 를 설정하세요." >&2
    exit 1
fi
AUTH=(-u "${DASHBOARD_USER}:${DASHBOARD_PASSWORD}")

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

show_help() {
    echo "Slack ↔ Jira 유저 매핑 등록"
    echo ""
    echo "사용법:"
    echo "  $0                         대화형 등록"
    echo "  $0 <slack_user_id> <jira_name>  직접 등록"
    echo "  $0 --list                  등록된 매핑 조회"
    echo "  $0 --help                  이 도움말"
    echo ""
    echo "Slack 유저 ID 찾는 방법:"
    echo "  Slack에서 유저 프로필 클릭 → 더보기(⋯) → 멤버 ID 복사"
}

list_mappings() {
    echo -e "${YELLOW}등록된 매핑 목록:${NC}"
    echo ""
    if ! response=$(curl -s --fail "${AUTH[@]}" "${API_URL}"); then
        echo -e "${RED}서버에 연결할 수 없습니다. Spring Boot가 실행 중인지 확인하세요.${NC}"
        exit 1
    fi
    echo "$response" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if not data:
    print('  (등록된 매핑이 없습니다)')
else:
    print(f'  {\"Slack ID\":<16} {\"Slack 이름\":<20} {\"Jira 이름\":<20}')
    print(f'  {\"-\"*16} {\"-\"*20} {\"-\"*20}')
    for m in data:
        sid = m.get('slackUserId', '')
        sname = m.get('slackDisplayName', '') or ''
        jname = m.get('jiraDisplayName', '')
        print(f'  {sid:<16} {sname:<20} {jname:<20}')
" 2>/dev/null || echo "$response"
}

register_mapping() {
    local slack_id="$1"
    local jira_name="$2"

    response=$(curl -s "${AUTH[@]}" -X POST "${API_URL}" \
        -H "Content-Type: application/json" \
        -d "{\"slackUserId\": \"${slack_id}\", \"jiraDisplayName\": \"${jira_name}\"}") || true

    status=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','error'))" 2>/dev/null || echo "error")

    if [ "$status" = "created" ] || [ "$status" = "updated" ]; then
        echo -e "${GREEN}✅ 등록 완료: ${slack_id} → ${jira_name} (${status})${NC}"
    else
        echo -e "${RED}❌ 등록 실패: ${response}${NC}"
    fi
}

interactive_register() {
    echo -e "${YELLOW}Slack ↔ Jira 유저 매핑 등록${NC}"
    echo ""

    read -p "Slack 유저 ID (예: U03L1TJ0EBB): " slack_id
    if [ -z "$slack_id" ]; then
        echo -e "${RED}Slack 유저 ID는 필수입니다.${NC}"
        exit 1
    fi

    read -p "Jira displayName (예: 김영현): " jira_name
    if [ -z "$jira_name" ]; then
        echo -e "${RED}Jira displayName은 필수입니다.${NC}"
        exit 1
    fi

    register_mapping "$slack_id" "$jira_name"
}

# 메인
case "${1:-}" in
    --help|-h)
        show_help
        ;;
    --list|-l)
        list_mappings
        ;;
    "")
        interactive_register
        ;;
    *)
        if [ -z "$2" ]; then
            echo -e "${RED}사용법: $0 <slack_user_id> <jira_display_name>${NC}"
            exit 1
        fi
        register_mapping "$1" "$2"
        ;;
esac
