---
name: artifact-upload
description: HTML 아티팩트(단일 파일 또는 html+자산 폴더)를 sol dashboard 아티팩트 갤러리에 업로드/수정하고 공유 링크를 받는다. "아티팩트 올려줘", "이 HTML 공유해줘/링크 만들어줘", "아티팩트 수정해줘/교체해줘", "Claude 아티팩트 팀에 공유" 같은 요청에 사용.
---

# sol dashboard 아티팩트 업로드

팀 내부 sol dashboard 에는 HTML 아티팩트 갤러리가 있다. HTML 을 업로드하면
`/artifacts/view/{id}/` 링크가 생긴다. **열람에도 sol 계정 로그인이 필요하다**
(v0.0.73 부터 — 링크를 열면 브라우저 로그인 창에 sol/sol 입력). 이 파일 하나로
충분하다 — 별도 스크립트 없이 아래 curl 패턴을 상황에 맞게 조립해 실행하면 된다.

- 서버: `https://tidiness-pointed-amuser.ngrok-free.dev` (ngrok 터널.
  환경변수 `ARTIFACT_SERVER_URL` 이 있으면 그 값을 우선 사용)
- 인증: HTTP Basic `sol:sol` — 업로드·수정·열람 전부 동일 계정
  (`ARTIFACT_SERVER_AUTH` 있으면 우선 사용)
- 필요 도구: bash + curl 뿐

## 케이스 1 — HTML 단일 파일

```bash
U="${ARTIFACT_SERVER_URL:-https://tidiness-pointed-amuser.ngrok-free.dev}"
curl -s -u "${ARTIFACT_SERVER_AUTH:-sol:sol}" -X POST "$U/api/artifacts" \
  -H 'ngrok-skip-browser-warning: 1' \
  -F "html=<report.html;type=text/html;charset=utf-8" \
  -F "title=주간 리포트" -F "author=홍길동"        # title/author 는 선택
```

`title` 을 생략하면 HTML `<title>` 태그 → `filename` 필드 → "제목 없는 아티팩트" 순으로 자동 결정.

## 케이스 2 — 저장된 웹페이지 (html + 자산 폴더)

`page.html` + `page_files/` 처럼 HTML 이 상대 참조하는 폴더가 있으면, 파일마다
`assets`(파일)와 `assetPaths`(상대경로) 필드를 **같은 순서로 쌍으로 반복** 전송한다.
파일이 많으니 아래 스니펫을 경로만 바꿔 실행:

```bash
U="${ARTIFACT_SERVER_URL:-https://tidiness-pointed-amuser.ngrok-free.dev}"
HTML="page.html"; DIR="page_files"          # ← 실제 경로로 변경 (DIR 은 폴더 "자체")
base="$(dirname "$DIR")"
args=( -s -u "${ARTIFACT_SERVER_AUTH:-sol:sol}" -H 'ngrok-skip-browser-warning: 1' -X POST
       -F "html=<$HTML;type=text/html;charset=utf-8" )
while IFS= read -r -d '' f; do
  args+=( -F "assets=@$f" -F "assetPaths=${f#"$base"/}" )
done < <(find "$DIR" -type f -print0)
curl "${args[@]}" "$U/api/artifacts"
```

핵심: `assetPaths` 는 **HTML 이 참조하는 상대 경로와 문자 그대로 일치**해야 한다.
HTML 에 `<img src="page_files/img.png">` 라면 assetPaths 도 `page_files/img.png`.
위 스니펫은 `DIR` 의 폴더명을 경로 접두사로 보존하므로 이 규칙을 자동으로 만족한다.

## 케이스 3 — 기존 아티팩트 수정 (같은 링크 유지)

이미 올린 아티팩트의 내용을 바꿀 때는 **새로 올리지 말고 PUT** 을 쓴다 — id 와 공유 링크가
그대로 유지되어 이미 링크를 받은 사람이 새 내용을 보게 된다. 사용법은 케이스 1/2 와 같고
`POST /api/artifacts` 를 `PUT /api/artifacts/{id}` 로 바꾸기만 하면 된다:

```bash
# 어떤 id 인지 모르면 목록에서 제목으로 찾는다
curl -s -u "${ARTIFACT_SERVER_AUTH:-sol:sol}" -H 'ngrok-skip-browser-warning: 1' "$U/api/artifacts"

# HTML 단일 파일 수정
curl -s -u "${ARTIFACT_SERVER_AUTH:-sol:sol}" -X PUT "$U/api/artifacts/7" \
  -H 'ngrok-skip-browser-warning: 1' \
  -F "html=<report.html;type=text/html;charset=utf-8"

# 자산 동반 수정: 케이스 2 스니펫에서 마지막 줄만 교체
curl "${args[@]}" -X PUT "$U/api/artifacts/7"
```

주의:
- **PUT 은 전체 교체다.** 자산이 있는 아티팩트를 수정할 때 자산을 다시 보내지 않으면
  기존 자산이 모두 삭제된다 — html 만 고치더라도 폴더를 함께 다시 전송할 것.
- 제목을 안 보내면 새 HTML 의 `<title>` → 파일명 → **기존 제목 유지** 순으로 결정된다.
- 존재하지 않는 id 면 404.

## 응답과 산출물

성공: `{"id": 7, "title": "...", "assetCount": 2}` → 공개 링크는
`{서버}/artifacts/view/{id}/` (**trailing slash 필수** — 구 형식은 301 리다이렉트).

업로드 후 링크가 열리는지 확인하고, **공개 링크를 사용자에게 전달하는 것이 최종 산출물**:

```bash
curl -s -o /dev/null -w "%{http_code}" -u "${ARTIFACT_SERVER_AUTH:-sol:sol}" \
  -H 'ngrok-skip-browser-warning: 1' "$U/artifacts/view/{id}/"   # 200 이어야 정상 (무인증이면 401)
```

## 인라인 댓글/리뷰 (v0.0.75) — 웹 UI 전용

아티팩트에 Google Docs 스타일 인라인 댓글을 달 수 있다. **댓글/리뷰는 웹 UI 에서만** 하고,
curl 로는 다루지 않는다. 사용자에게 리뷰 링크를 안내하면 된다:
`{서버}/artifacts/review/{id}` (브라우저에서 sol 로그인 → 텍스트 드래그해 우측 레일에 코멘트).
주의: 이후 이 아티팩트를 PUT 으로 **수정하면 기존 댓글이 원문 위치를 잃을 수 있다**(삭제되지는
않고 "위치 없음" 고아 카드로 잔존).

## 경로 규칙과 한도

- 한글·공백 경로 그대로 지원 (`Shard 모델 - Claude_files/s.js` OK). 선행 `./` 는 서버가 제거
- 거부(400): 절대경로(`/...`), `..`/`.` 세그먼트, 백슬래시, 500자 초과, 중복 경로
- MIME 은 서버가 확장자로 결정 — 확장자 없는 파일(예: 구글폰트 `css2`)은
  `application/octet-stream` 이 되어 브라우저가 CSS/JS 로 해석하지 않는다

| 항목 | 한도 | 초과 시 |
|---|---|---|
| HTML | 5MB | 400 + 한글 error 메시지 |
| 자산 파일당 | 5MB | 400 |
| 자산 합계 | 25MB | 400 |
| 자산 개수 | 200개 | 400 |
| 요청 전체 | 40MB (컨테이너) | 413 또는 커넥션 중단 |

업로드 전 `find "$DIR" -type f -size +5M` 과 `du -sb "$DIR"` 로 한도를 먼저 확인하면
컨테이너 중단(메시지 없는 실패)을 피할 수 있다.

## Claude 아티팩트를 올리는 경우

Claude 공유 링크(claude.ai/...)는 직접 등록 불가(iframe 차단). 아티팩트 화면
우측 상단 메뉴에서 **HTML 로 복사(파일로 저장) 또는 다운로드**한 뒤 케이스 1 로 올린다.
브라우저 "페이지 저장(완전한 웹페이지)"으로 받은 `x.html` + `x_files/` 는 케이스 2.

## 오류 대응

| 증상 | 원인/대응 |
|---|---|
| 401 | Basic Auth 누락/오타 — `-u sol:sol` 확인 |
| 400 + `{"error":"..."}` | 응답의 한글 메시지가 원인을 그대로 설명함 (경로/크기/개수) |
| `assets(N)와 assetPaths(M) 개수가 다릅니다` | 파일과 경로 필드를 쌍으로 반복했는지 확인 |
| 413 / 커넥션 끊김 | 요청 전체 40MB 초과 — 자산을 줄여서 재시도 |
| PUT 이 404 | 해당 id 아티팩트 없음 — `GET /api/artifacts` 로 id 확인 |
| 수정 후 이미지/CSS 깨짐 | PUT 은 전체 교체 — 자산 폴더를 함께 다시 보냈는지 확인 |
| 첫 방문 시 ngrok 경고 페이지 | 무료 도메인 특성. 브라우저에서 "Visit Site" 1회 클릭 (curl 은 `ngrok-skip-browser-warning: 1` 헤더) |

## 설치 (파일 1개)

이 SKILL.md 를 대상 서버의 `~/.claude/skills/artifact-upload/SKILL.md` 로 복사하면
Claude Code 가 자동 인식한다:

```bash
ssh 대상서버 'mkdir -p ~/.claude/skills/artifact-upload'
scp docs/claude-skills/artifact-upload/SKILL.md 대상서버:~/.claude/skills/artifact-upload/
```
