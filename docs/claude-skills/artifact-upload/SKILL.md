---
name: artifact-upload
description: HTML 아티팩트(단일 파일 또는 html+자산 폴더)를 sol dashboard 공개 갤러리에 업로드하고 로그인 없이 누구나 열 수 있는 공개 링크를 받는다. "아티팩트 올려줘", "이 HTML 공유해줘/공개 링크 만들어줘", "Claude 아티팩트 팀에 공유" 같은 요청에 사용.
---

# sol dashboard 아티팩트 업로드

팀 내부 sol dashboard 에는 공개 HTML 아티팩트 갤러리가 있다. HTML 을 업로드하면
`/artifacts/view/{id}/` 공개 링크가 생겨 **Claude 팀플랜/대시보드 계정이 없는 사람도
링크만으로 열람**할 수 있다. 이 스킬은 그 갤러리에 파일을 올리는 방법이다.

- 서버: `https://tidiness-pointed-amuser.ngrok-free.dev` (ngrok 터널)
- 업로드 인증: HTTP Basic `sol:sol` (뷰어 링크는 무인증 공개)
- 환경변수 `ARTIFACT_SERVER_URL` / `ARTIFACT_SERVER_AUTH` 로 오버라이드 가능

## 빠른 사용 — 동봉 스크립트

이 스킬 디렉토리의 `upload.sh` 를 사용한다 (bash + curl 만 필요):

```bash
# 1) HTML 단일 파일
./upload.sh report.html

# 2) 저장된 웹페이지 (html + 자산 폴더) — 폴더는 "폴더 자체"를 넘긴다
./upload.sh "page.html" "page_files"

# 3) 제목/작성자 지정
./upload.sh report.html "" "주간 리포트" "김영현"
```

성공 시 마지막 줄에 `✅ 공개 링크: .../artifacts/view/{id}/` 가 출력된다.
**이 링크를 사용자에게 전달하는 것이 이 작업의 최종 산출물이다.**

## 스크립트 없이 curl 직접

```bash
U=https://tidiness-pointed-amuser.ngrok-free.dev

# HTML 단일 파일 (JSON 도 되지만 멀티파트가 이스케이프 걱정이 없어 권장)
curl -s -u sol:sol -X POST "$U/api/artifacts" \
  -F "html=<report.html;type=text/html;charset=utf-8" \
  -F "title=주간 리포트"

# 자산 동반: assets(파일)와 assetPaths(상대경로)를 같은 순서로 쌍으로 반복
curl -s -u sol:sol -X POST "$U/api/artifacts" \
  -F "html=<page.html;type=text/html;charset=utf-8" \
  -F "assets=@page_files/style.css"  -F "assetPaths=page_files/style.css" \
  -F "assets=@page_files/chart.js"   -F "assetPaths=page_files/chart.js"
```

응답: `{"id": 7, "title": "...", "assetCount": 2}` → 공개 링크는
`{서버}/artifacts/view/{id}/` (**trailing slash 필수** — 구 형식은 301 리다이렉트됨).

## 자산 경로 규칙 (가장 흔한 실수)

`assetPaths` 는 **HTML 이 참조하는 상대 경로와 문자 그대로 일치**해야 한다.
HTML 에 `<img src="page_files/img.png">` 라면 assetPaths 도 `page_files/img.png`.

- 한글·공백 경로 그대로 지원 (`Shard 모델 - Claude_files/s.js` OK)
- 거부되는 경로(400): 절대경로(`/...`), `..` 또는 `.` 세그먼트, 백슬래시, 500자 초과, 중복
- 선행 `./` 는 서버가 자동 제거
- MIME 은 서버가 확장자로 결정 — 확장자 없는 파일(예: 구글폰트 `css2`)은
  `application/octet-stream` 이 되어 브라우저가 CSS/JS 로 해석하지 않는다

## 한도

| 항목 | 한도 | 초과 시 |
|---|---|---|
| HTML | 5MB | 400 + 한글 error 메시지 |
| 자산 파일당 | 5MB | 400 |
| 자산 합계 | 25MB | 400 |
| 자산 개수 | 200개 | 400 |
| 요청 전체 | 40MB (컨테이너) | 413 또는 커넥션 중단 |

## Claude 아티팩트를 올리는 경우

Claude 공유 링크(claude.ai/...)는 직접 등록 불가(iframe 차단). 아티팩트 화면
우측 상단 메뉴에서 **HTML 로 복사(파일로 저장) 또는 다운로드**한 뒤 그 파일을 올린다.
브라우저 "페이지 저장(완전한 웹페이지)"으로 받은 `x.html` + `x_files/` 도 방식 2로 올리면 된다.

## 검증과 오류 대응

업로드 후 반드시 공개 링크가 열리는지 확인한다:

```bash
curl -s -o /dev/null -w "%{http_code}" -H 'ngrok-skip-browser-warning: 1' \
  "$U/artifacts/view/{id}/"   # 200 이어야 정상
```

| 증상 | 원인/대응 |
|---|---|
| 401 | Basic Auth 누락/오타 — `-u sol:sol` 확인 |
| 400 + `{"error":"..."}` | 응답의 한글 메시지가 원인을 그대로 설명함 (경로/크기/개수) |
| `assets(N)와 assetPaths(M) 개수가 다릅니다` | 파일과 경로 필드를 쌍으로 반복했는지 확인 |
| 413 / 커넥션 끊김 | 요청 전체 40MB 초과 — 자산을 줄여서 재시도 |
| 첫 방문 시 ngrok 경고 페이지 | 무료 도메인 특성. 브라우저에서 "Visit Site" 1회 클릭 (curl 은 `ngrok-skip-browser-warning: 1` 헤더) |

## 다른 서버에 설치

이 디렉토리(SKILL.md + upload.sh)를 대상 서버의 `~/.claude/skills/artifact-upload/` 에
복사하면 Claude Code 가 자동 인식한다:

```bash
scp -r docs/claude-skills/artifact-upload ubuntu@대상서버:~/.claude/skills/
chmod +x ~/.claude/skills/artifact-upload/upload.sh
```
