#!/usr/bin/env bash
# sol dashboard 공개 아티팩트 갤러리 업로더
# 사용법: upload.sh <html파일> [자산폴더] [제목] [작성자]
#   자산폴더: HTML 이 상대 참조하는 폴더 (예: page_files). 경로 접두사가 보존되어야
#             하므로 "폴더 자체"를 넘긴다 — 내부 파일은 page_files/... 로 전송된다.
# 환경변수: ARTIFACT_SERVER_URL, ARTIFACT_SERVER_AUTH 로 서버/계정 오버라이드 가능.
set -euo pipefail

URL="${ARTIFACT_SERVER_URL:-https://tidiness-pointed-amuser.ngrok-free.dev}"
AUTH="${ARTIFACT_SERVER_AUTH:-sol:sol}"

HTML="${1:?사용법: upload.sh <html파일> [자산폴더] [제목] [작성자]}"
DIR="${2:-}"
TITLE="${3:-}"
AUTHOR="${4:-}"

[ -f "$HTML" ] || { echo "❌ HTML 파일이 없습니다: $HTML" >&2; exit 1; }
[ "$(stat -c%s "$HTML")" -le $((5 * 1024 * 1024)) ] || { echo "❌ HTML 이 5MB 제한을 초과합니다." >&2; exit 1; }

args=( -s -u "$AUTH" -H 'ngrok-skip-browser-warning: 1'
       -F "html=<$HTML;type=text/html;charset=utf-8"
       -F "filename=$(basename "$HTML" | sed 's/\.[Hh][Tt][Mm][Ll]\?$//')" )
[ -n "$TITLE" ] && args+=( -F "title=$TITLE" )
[ -n "$AUTHOR" ] && args+=( -F "author=$AUTHOR" )

if [ -n "$DIR" ]; then
  [ -d "$DIR" ] || { echo "❌ 자산 폴더가 없습니다: $DIR" >&2; exit 1; }
  DIR="${DIR%/}"
  base="$(dirname "$DIR")"
  count=$(find "$DIR" -type f | wc -l)
  [ "$count" -le 200 ] || { echo "❌ 자산 파일이 200개를 초과합니다 ($count개)." >&2; exit 1; }
  big=$(find "$DIR" -type f -size +5M | head -1)
  [ -z "$big" ] || { echo "❌ 5MB 초과 자산: $big" >&2; exit 1; }
  total=$(find "$DIR" -type f -printf '%s\n' | awk '{s+=$1} END{print s+0}')
  [ "$total" -le $((25 * 1024 * 1024)) ] || { echo "❌ 자산 합계가 25MB 를 초과합니다." >&2; exit 1; }
  # 서버 계약: assets 파일과 assetPaths 텍스트 필드를 같은 순서(쌍)로 반복 전송.
  # 상대 경로는 HTML 의 참조와 정확히 같아야 한다 (예: page_files/img.png).
  while IFS= read -r -d '' f; do
    rel="${f#"$base"/}"
    args+=( -F "assets=@$f" -F "assetPaths=$rel" )
  done < <(find "$DIR" -type f -print0)
fi

resp=$(curl "${args[@]}" -X POST "$URL/api/artifacts")
echo "$resp"
id=$(printf '%s' "$resp" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
if [ -n "$id" ]; then
  echo "✅ 공개 링크: $URL/artifacts/view/$id/"
else
  echo "❌ 업로드 실패 — 위 응답의 error 메시지를 확인하세요." >&2
  exit 1
fi
