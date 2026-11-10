#!/usr/bin/env bash
# 문서 소스 하나를 받아 조각·번호·임베딩까지 다시 만든다.
#   scripts/rag-build.sh <소스> [--dry-run] [--ref <브랜치|태그|커밋>]
# GitHub 토큰은 이 실행에만 넘긴다 — 계속 떠 있는 rag 서버 컨테이너에는 넣지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 1 ] || { echo "usage: scripts/rag-build.sh <source> [--dry-run] [--ref REF]" >&2; exit 2; }

RAG_GITHUB_TOKEN="$(python3 - <<'PY'
import re
for line in open(".env", encoding="utf-8"):
    m = re.match(r"\s*GITHUB_ALERT_TOKEN\s*=\s*(.*)", line)
    if m:
        print(m.group(1).strip().strip("'\""))
        break
PY
)"
export RAG_GITHUB_TOKEN
[ -n "$RAG_GITHUB_TOKEN" ] || echo "[rag-build] GITHUB_ALERT_TOKEN 이 .env 에 없다 — 공개 저장소나 --from-dir 만 된다" >&2

exec sudo -n --preserve-env=RAG_GITHUB_TOKEN docker-compose --profile rag run --rm --no-deps -T \
    -e RAG_GITHUB_TOKEN rag python -m app.build "$@"
