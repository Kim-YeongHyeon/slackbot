# Jira Slack Bot

Slack 채널에서 자연어로 메시지를 보내면 AI가 자동 분류하여 Jira 이슈를 생성하는 봇.

## 주요 기능

| 기능 | 설명 |
|---|---|
| AI 이슈 자동 생성 | 자연어 → Haiku 의도 분류 → Sonnet 상세화(제목/SP/타입) → Jira 등록 |
| 에픽 생성 | `에픽`/`epic` 키워드가 포함되면 AI 분류 없이 결정적으로 Epic 생성 (Story Point·워크플로 버튼 없음, 스토리/버그와 구별) |
| 스프린트 리포트 | 담당자별 진행 상황, SP 집계 |
| 작업 조회 | 내 작업 / 팀원 작업 조회 |
| 스레드 액션 | 이슈 스레드에서 하위작업 생성, 댓글 추가, 설명 수정, 완료 처리 |
| 중복 감지 | 이슈 생성 시 DB에서 유사 이슈 검색 후 경고 |
| 이슈 검색 | 키워드로 이슈 제목 검색 (모든 상태 포함) |
| 이슈 키 조회 카드 | `@지라 ES2-123` → 유형/상태/담당자/보고자/SP/스프린트/설명 카드 + 상태 전환 버튼 (로컬 DB 미보유 시 Jira 라이브 조회 폴백) |
| 담당자 지정 | `@지라 할당 ES2-123 홍길동`(또는 `@멘션`), 이슈 스레드에서는 `@지라 담당자 홍길동` 단축형 |
| 개인 할당 DM 알림 | Jira에서 이슈가 본인에게 할당되면 즉시 DM (등록 사용자 대상, 기본 ON, `@지라 할당알림 off` 로 끄기, 셀프할당 제외) |
| 리마인더 | opt-in 사용자에게 미해결 이슈 DM. **일일**(평일 09:00, 현재 스프린트) + **격주**(월 09:30, 전체 미해결). 소유 기준 = 담당자, 없으면 보고자 |
| 인터랙티브 버튼 | 이슈 생성 알림에 "진행 중" / "완료" 버튼 → 클릭으로 Jira 상태 전환 |
| 브랜치 만들기 | "진행 중" 전환 시 **repo 선택 버튼**을 띄워 클릭하면 봇이 GitHub API로 브랜치 직접 생성(브랜치명은 Claude가 한글 요약→영어 슬러그, `feature/`·`bugfix/`). 브랜치명에 이슈키가 있어 Jira 개발 패널에 자동 연결. `github.token` 미설정 시 Jira 개발 패널 링크 안내로 폴백 |
| Notion 버그 동기화 | 버그 완료 시 원인/해결방법(Claude 요약)을 Notion '버그 해결 기록' DB에 적재. 전체 버그는 '버그 현황' DB에 해결/미해결 구분해 동기화 (`@지라 notion백필`) |
| 채널 제한 | 허용된 채널에서만 봇 동작 |

### 성능/안정성 (v0.0.21)

- **검색 선행 sync TTL**: 검색 전 freshness 용 Jira sync(2~3초)를 60초 TTL로 게이트 — 연속 검색은 첫 번째만 지연.
- **활성 스프린트 캐시**: `getActiveSprint()` 를 Caffeine 5분 TTL 캐시로 — 호출마다 Jira 왕복 2회(보드+스프린트) 제거.
- **fullSync 공유 fetch**: sync 와 삭제-정리(prune)가 sprint/backlog 목록을 1회만 조회 (기존 2배 왕복 제거).
- **DB 쿼리 최적화**: `내작업`·시맨틱 검색의 `findAll()` 전체 로드 제거(전용 쿼리/상한 150건), 중복 감지 키워드별 N회 LIKE → 1회 조회 + 집계.
- **인덱스**: `issues(status_category)`, `issues(sprint_id)`, `issues(completed_at)`.
- **운영 설정**: `show-sql` off, 로깅 INFO(트러블슈팅 시 `LOG_LEVEL_APP=DEBUG`), HikariCP `maximum-pool-size: 15`.

### 웹 대시보드 (v0.0.26, 외부 접근 v0.0.30)

- 사내망/SSH 포워딩: `http://<호스트IP>:8080/dashboard/` (인증 없음)
- **외부(인터넷)**: `https://<ngrok-domain>/dashboard/` — Go봇이 :8080 으로 리버스 프록시하며
  **Basic Auth**(`DASHBOARD_USER`/`DASHBOARD_PASSWORD`, 둘 다 설정 시에만 활성)로 보호.
  화이트리스트 경로만 노출: `/dashboard/`, `/api/dashboard/`, `/api/user-mappings`, `/actuator/health`
  — 그 외 Spring API 는 터널에서 계속 404.

| 탭 | 내용 |
|---|---|
| 개요 | KPI 카드(전체/미해결/진행 중/스프린트 SP 완료율/정체/등록 사용자) + 마지막 동기화 + [지금 동기화] |
| 스프린트 | 상태 분포 도넛 · 담당자별 미해결 SP · 정체(7일+) 이슈 목록 |
| 추이 | 주간 생성 vs 해결 라인(4~26주) · 주별 평균 해결 소요시간. **[히스토리 백필]** 버튼으로 Jira 전체 이슈를 1회 적재해 과거 기록 표시 (v0.0.35) |
| 담당자 부하 | 담당자별 미해결 수/SP/정체 (미배정 포함). **전체 / 현재 스프린트 토글** (v0.0.34) |
| 버그 | 버그 비율 · 주간 발생 vs 해결 · 미해결 버그 목록 (**전체 / 현재 스프린트 토글**). **해결된 버그**는 펼칠 때만 Jira 라이브 조회(완료일 최신순, 완료 버그 내 검색) — 완료 이슈는 로컬에서 prune 되므로 라이브가 진실. (v0.0.34) |
| 이슈 목록 | 상태/담당자/유형 필터 + 키워드 검색 (최근 갱신순 200건). **키 헤더 클릭으로 오름/내림 정렬** (v0.0.34) |
| 사용자 관리 | Slack↔Jira 매핑 등록(accountId·Slack 실명 자동 해석)/삭제, 리마인더·할당알림 토글 |
| PR 현황 | 설정된 repo 전체의 열린 PR + 작성자 + **연결 Jira 이슈**(브랜치명/제목의 이슈 키로 자동 조인 — 상태·담당자·링크). **레포/작성자 필터 + 생성·갱신일 정렬(↑↓)** (v0.0.31, 클라이언트 필터라 refetch 없음), 생성일 컬럼 표시. 5분 캐시. **토큰에 Pull requests: Read-only 권한 필요** (없으면 탭에 안내 표시) |
| 봇 상태 | 서버 health · 최근 의도분류 실패 로그 |
| 기능요청 | 게시판 (v0.0.32) — 누구나 제목/내용/이름으로 요청 등록 → **관리자에게 Slack DM** (`FEATURE_REQUEST_NOTIFY_USER`, 비우면 DM 생략). 구현 후 완료 처리(되돌리기 가능, 완료일 기록). `feature_requests` 테이블(Flyway V3), API `/api/feature-requests` (GET/POST/PATCH) |

API: `/api/dashboard/*` (summary·sprint·trends·workload·bugs·issues·intent-failures·actions/sync),
`/api/user-mappings` (GET/POST/DELETE/PATCH — POST 는 Jira accountId 자동 해석).
데이터는 전부 로컬 DB — 새로고침해도 Jira API 호출 없음.

### 스키마 마이그레이션 — Flyway (v0.0.24)

- 스키마는 **`src/main/resources/db/migration/V<N>__*.sql` 마이그레이션으로만 변경**한다. `ddl-auto=validate` 라
  엔티티만 고치면 기동이 실패한다(의도된 동작 — 조용한 드리프트 차단).
- 컬럼/테이블 추가 절차: ① `V2__add_xxx.sql` 작성 ② 엔티티 동기 수정 ③ 테스트 ④ 배포(기동 시 자동 적용).
- 기존 운영 DB 는 `baseline-on-migrate` 로 V1(2026-06-11 스냅샷)이 적용된 것으로 처리됐고,
  신규(빈) DB 는 V1 부터 실행되어 전체 스키마가 만들어진다 (신규 설치 경로 검증 완료).
- 테스트(H2)는 Flyway 를 끄고 기존 create-drop 유지 (`application-test.yml`).
- 백업: 전환 직전 풀 백업 `~/backups/jirabot-pre-flyway-*.dump` (복원: `pg_restore -U jirabot -d jirabot <dump>`).

### Jira 웹훅 수신 경로 (v0.0.23, 등록 완료 v0.0.28)

Jira → ngrok 터널(:3000) → **Go 봇 `/api/jira/webhook` 프록시** → Spring `/api/jira/webhook`.
ngrok 이 Go 봇만 노출하므로 프록시가 필수다. 등록은 Jira **사이트 관리자**가 설정 → 시스템 → 웹훅에서
(또는 관리자 토큰으로 `POST /rest/webhooks/1.0/webhook`):

- URL: `https://<ngrok-domain>/api/jira/webhook?token=<JIRA_WEBHOOK_SECRET>`
- 이벤트: 이슈 → **업데이트됨** (`jira:issue_updated`)
- JQL 필터: `project = ES2`

**2026-06-12 등록 완료** (`webhooks/1.0/webhook/1`, 관리자 `JIRA_ADMIN_EMAIL` 계정) — 할당 DM·스레드
상태변경 알림·버그 완료 Notion 자동 동기화 라이브 활성. **ngrok 도메인이 바뀌면 웹훅 URL 재등록 필요.**
수신/할당 DM 판정(발송·생략 사유)은 INFO 로그로 남는다 (`Jira webhook received`, `Assign DM sent/skipped`).

### 분류 프롬프트 개선 (v0.0.33)

`claude -p`(headless) 분류 호출의 시스템 프롬프트(`prompts/*.md`)를 개선했다. 검증된 사실 기준:

- `--system-prompt-file` 은 시스템 프롬프트를 **대체**(append 아님)하므로 각 파일은 자기완결적이어야 한다.
- `--bare`(CLAUDE.md/skill/hook 스킵)는 이 호스트의 **구독 인증을 깨므로 사용 불가**(라이브 확인: "Not logged in").
  따라서 분류 호출마다 프로젝트 컨텍스트가 로드되지만, 시스템 롤은 prompt 파일이 덮어쓴다.
- `--json-schema` 는 구조화 출력을 **툴 호출**로 처리해 `--max-turns` 를 1 더 소비 → 현재 `max-turns 1/2` 와
  충돌(`error_max_turns`)하므로 채택하지 않음. 출력 견고성은 파서의 fence-strip 으로 보장.
- 개선 핵심: 흩어진 disambiguation 을 **우선순위 결정 절차(first-match-wins)**로 재구성, `skip`(봇과의 사회적/내용없는
  상호작용) vs `unknown`(Jira 무관 잡담·잡지식)의 경계를 명확화, 통계(숫자/집계) vs 스크럼(서술형 진행)의 경계 추가.
- **회귀 가드**: `IntentClassifierEvalTest`(90케이스, 실제 CLI 호출)로 전후 정확도를 측정.
  실행: `./gradlew test -Dintent.eval=true --tests "*IntentClassifierEvalTest"`.
- Story Point 기준을 [docs/story-point-guide.md](docs/story-point-guide.md) 와 정합(1·2·3·5·8, **8이 상한**, 13 출력 금지).
  `prompts/sonnet-classifier.md` 와 인라인 폴백 `ClaudeApiClientImpl.SYSTEM_PROMPT` 양쪽 정리.

### 터널 경유 대시보드 접근 (v0.0.30)

OCI 서버라 SSH 포워딩 없이는 대시보드를 못 보던 문제 해결 — 기존 ngrok 도메인을 재사용해
Go봇이 대시보드 경로만 Basic Auth 를 걸어 :8080 으로 프록시한다 (위 "웹 대시보드" 섹션 참고).
Slack/Jira webhook 경로는 기존과 동일하게 무인증(각자 서명/token 검증).

### 상태변경 알림 '변경자' 라인 제거 (v0.0.37)

웹훅 상태변경 스레드 알림에서 "변경자" 라인을 제거했다. 봇이 일으킨 전환(슬랙 버튼/명령)은 단일 Jira API
토큰으로 호출돼 webhook actor 가 항상 토큰 소유자로 기록되므로(실제 클릭자와 무관) "변경자: @토큰소유자"가
오해를 줬다. 버튼 클릭 시 원본 메시지는 `buildTransitionedBlocks` 가 실제 클릭자 이름으로 이미 갱신한다.

### 스킬 eval 하네스 + 도메인 글로서리 + 파싱 검증 (v0.0.62)

분류 스킬의 품질을 **측정 가능**하게 만들고 도메인 정확도를 올렸다:
- **스킬 eval 하네스**: `SkillClassifierEvalTest`(opt-in, `-Dskill.eval=true`) + 골든셋 20케이스
  (`src/test/resources/skill-eval/cases.json`, 실제 팀 도메인 어휘 사용). 검증 축 6개 — type 정확도(≥0.90),
  SP 스케일 {1,2,3,5,8}(100%), 제목 위생: 명령어구·이슈키 없음(100%), 도메인 토큰 보존(≥0.80),
  SP 기대범위(≥0.70), summary 구조 마커(≥0.70). 이후 스킬 수정 시 전/후 비교 필수(L12 확장).
  주의: test 프로파일은 cli-path=/bin/true 라 eval 테스트가 실값으로 오버라이드. build.gradle 에 skill.eval 포워딩.
- **도메인 글로서리**: 실제 이슈 1,744건에서 컴포넌트 태그([ES2M]/[EVI]/[SDK]/[Shaper]/evi-crypto/KMS…)와
  용어(HEaaN, IVF, VCT, shard, ctxt, proto, BatchEncrypt…)를 추출해 skill-bug/skill-story 에 `<domain_context>`
  추가 — 태그를 title 앞에 보존, 용어 번역/일반화 금지. 도메인 few-shot 각 1개 추가.
- **파싱 검증 수정**(실 버그): JSON 으로는 유효하지만 필수 필드가 빠진 모델 응답이 그대로 통과해
  "null" 제목 티켓이 생길 수 있었다(eval 에서 실측). type/title/SP∈{1,2,3,5,8} 검증 실패 시 재시도.
- **eval 결과: 20/20 전 축 1.000** (type/SP/제목위생/도메인토큰/SP범위/구조마커).

### 자연어 담당자 변경 (v0.0.61)

"@지라 ES2-1190 담당자를 최아록으로"가 **search로 오분류**돼 "검색 결과가 없습니다"로 응답하던 문제 수정.
담당자 변경 기능 자체는 있었으나(`할당 KEY 이름` 키워드, 스레드 `담당자 이름`) 이 자연어 어순은 어떤 결정적
파서에도 안 걸려 Haiku로 넘어갔었다.
- `IssueCommandParser.parseAssign`: 키 1개 + 담당자 키워드 + 이름 토큰(한/영/@멘션). 붙은 조사 분리
  ("최아록으로"→최아록, lazy+lookahead). 조회 어휘(누구야/알려줘)는 제외 → 변경 명령으로 오인 안 함.
- 기존 `executeAssign` 재사용(매핑→Jira user search 폴백 해석 포함). 라우팅 1.42차(키워드 `할당`의 NL 짝).
- "ES2-123 담당자 누구야"는 카드 필러에 담당자/누구 어휘를 추가해 **이슈 카드**(담당자 필드 표시)로 응답.

### 분류별 전용 스킬 분리 — skill-bug / skill-story / skill-pr-import (v0.0.60)

목표 아키텍처(Haiku 의도분류 → **분류별 전용 Sonnet 스킬**) 정합화. LLM 경로 전수 감사 결과
버그/스토리가 공용 스킬 1개를 쓰고, PR import 는 용도가 다른 스킬(Slack 신고문 분류)을 재사용하고 있었다.
- **`prompts/skill-bug.md`**: 버그 신고 특화 — summary 를 현상→재현 경로(없으면 "재현 경로 불명", 발명 금지)→영향
  범위→단서 구조로, 로그/에러코드/엔드포인트는 **원문 보존**. FEATURE-override few-shot 포함.
- **`prompts/skill-story.md`**: 스토리 특화 — 목적/배경→요구사항→**완료 조건(AC) 불릿**. 사용자가 말한 범위
  제한("로컬스토리지면 충분")을 AC 로 추출, 미언급 요구사항 발명 금지. BUG-override few-shot 포함.
- **`prompts/skill-pr-import.md`**: PR 회고 등록 특화 — 요청문이 아닌 완료 작업 서술(무엇을/왜/어떻게),
  PR 템플릿 잡음(체크리스트/헤더) 제거, conventional-commit prefix 제거. SP 는 기간 기반으로 대체되므로 형식만.
- 선택: `ClaudeApiClientImpl.classifierPromptFileFor(intentHint)` — register_bug→bug 스킬, register_story→story
  스킬, 에픽/무힌트→공용. **2단 폴백**(스킬 없음→공용→인라인) 으로 부분 배포 안전. `classifyPr` 신규.
- 스킬 작성 기법: XML 태그 섹션, few-shot 4개(경계 케이스 포함), JSON-only 계약 (Anthropic best practices +
  semantic parsing 문헌 조사 반영). 세 스킬 모두 라이브 스모크 검증 완료.
- 검색(`sonnet-issue-search.md`)·웹훅 요약/카테고리는 이미 전용 스킬 — 감사 결과 목표 부합 확인.

### 내 작업 미완료-한정 질의 수정 (v0.0.59)

"보고자가 나인데 완료 안된 task 뭐가 있는지 알려줘"에 **완료된 task까지 모두 나오던 버그** 수정.
원인: 의도 분류(my_tasks)까지는 정확했지만 `handleWithIntent`의 `case "my_tasks" -> handleMyWork(event)`가
**원문을 버려서** "완료 안된"이라는 조건이 소실 → `generateMyReport`가 상태 무관 전체를 출력했다.
- `wantsIncompleteOnly(원문)` 결정적 감지(완료 안/않/못, 미완료, 안 끝난, 끝나지 않, 남은, 미해결, not done…
  — 긍정 질의 "완료한 작업"은 미매칭) → `generateMyReport(user, excludeDone=true)`.
- excludeDone 모드: 완료 이슈 제외, 진행 중/해야 할 일 섹션만 + "미완료 N건 · X SP" 요약.
  전부 완료면 축하 메시지. 키워드 `내작업`은 기존대로 완료 섹션 포함(회귀 가드 테스트).

### 분류 호출 대폭 고속화 — thinking 차단 + CLI 경량화 (v0.0.58)

`claude -p` 분류가 느리던 병목을 실측으로 분해해 제거. **프롬프트(skill)가 아니라 런타임이 병목**이었다:
- **thinking 토큰 차단** (`MAX_THINKING_TOKENS=0`, DefaultProcessRunner에서 주입) — 분류 JSON은 ~60토큰이면
  되는데 thinking이 호출당 350~450토큰을 선생성해 지연의 60~70%를 차지했다.
  실측: **Haiku 6.5s→2.4s, Sonnet 10s→4.4s**. 프로덕션 DB 기준 분류 평균 26s/p90 45s였음.
  **되돌리기: `.env` 에 `CLAUDE_DISABLE_THINKING=false` 한 줄 + 재시작** (`claude.disable-thinking`).
- **CLI 경량화 플래그** (`ClaudeCliFlags.LEAN_FLAGS`, 3개 분류기 공통): `--tools ""`(도구 스키마 제거),
  `--exclude-dynamic-system-prompt-sections`(git 상태 등 동적 섹션 + **CLAUDE.md 자동 주입 차단** — 프로젝트
  지침이 분류 컨텍스트에 섞이던 간섭 제거), `--no-session-persistence`(세션 jsonl 누적 중단 — 기존 418개/35MB),
  `--disable-slash-commands`. 시스템 프롬프트 **~22k→~4k 토큰**, 프롬프트 캐시 적중 안정화.
- **타임아웃 하향**: 의도 40→15s, Sonnet 60→20s — 정상 호출이 2~4s가 되어 재시도 3회 예산이 최악 45s로 제한.
- 정확도 검증: `IntentClassifierEvalTest`(90케이스) 전/후 비교 실시 (L12).

### 이슈 링크 조회 / 해제 (v0.0.57)

"ES2-123 링크 보여줘"(조회), "ES2-1 ES2-2 링크 해제"(해제)를 자연어로 처리. 링크 생성(v0.0.55)의 짝.
- 신규 `IssueCommandParser.parseLinkList`(1키+링크+조회 동사)·`parseUnlink`(2키+링크+해제 동사).
- 신규 클라이언트 `getIssueLinks`(GET issuelinks)·`deleteIssueLink`(DELETE, 204).
  **조회자 상대적** 처리: outwardIssue 항목은 "this <outward> 상대", inwardIssue 항목은 "this <inward> 상대"로 렌더.
- 해제는 대상 이슈의 링크 중 상대 키가 일치하는 linkId 를 찾아 삭제(재연결이 싸므로 확인 버튼 없이 즉시 실행).
  두 이슈 사이 링크가 없으면 안내.

### 기존 이슈 필드 수정 + 스프린트 이동 (v0.0.56)

"ES2-123 SP 3으로 변경", "제목을 '..'로", "마감일 금요일로", "우선순위 높음", "ES2-123 스프린트로 옮겨줘"를
자연어로 처리(기존 미지원 — 생성 시에만 설정 가능했음). 결정적 파싱(키 정확히 1개 + 필드 키워드):
- 신규 `IssueCommandParser.parseUpdate`: SP/제목/마감일/우선순위. 키의 숫자(-123)를 값으로 오인하지 않도록 키 제거 후 파싱.
  **SP는 팀 스케일 {1,2,3,5,8} 검증**(docs/story-point-guide.md), 벗어나면 거부. 제목은 따옴표 필수. 마감일은 절대/상대(오늘·내일·요일).
- 신규 클라이언트 `updateIssueFields(key, fields)`(PUT /rest/api/3/issue/{key}, 204)·`getPriorities`(@Cacheable).
  우선순위는 사용자 표현(높음/긴급/보통/낮음)→정규 버킷→사이트 실제 name(getPriorities 매칭, 로컬라이즈 대비, L4)으로 해석.
- 마감일 상대어(오늘/내일/모레/글피/다음주/요일)는 KST 기준으로 ISO 날짜 해석(요일은 다음 도래일).
- 제목/SP 성공 시 로컬 IssueEntity 도 동기화(대시보드/카드 일관성). 스프린트 이동은 기존 `moveToActiveSprint` 재사용.
- 실측: 라이브 우선순위 목록(Highest/High/Medium/Low/Lowest) 확인.

### 이슈 링크 생성 — blocks/relates/duplicate (v0.0.55)

"ES2-1352가 ES2-1532에 block 되고 있으니 연결해줘" 같은 자연어로 이슈 링크를 생성한다(기존 미지원).
결정적 파싱(프롬프트 무변경):
- 신규 `IssueCommandParser.parseLink`: 이슈 키 정확히 2개 + 관계 동사(block/막/블락/의존/중복/duplicate/relate/연결)일 때만 발동.
  한/영 어순과 능동·피동을 구분해 Jira 방향(`inwardIssue <inward> outwardIssue`)을 결정 — "A가 B에 막힘"=A is blocked by B(inward=A),
  "A가 B를 막음"=A blocks B(inward=B), "A duplicates B"(outward=A) 등. 방향 판별 불가 시 `ambiguous`.
- 신규 클라이언트 `getIssueLinkTypes`(@Cacheable, 사이트 정의 Blocks/Relates/Duplicate…)·`linkIssues(inward, outward, typeName)`(POST /rest/api/3/issueLink).
  관계→실제 타입은 name/inward/outward 부분일치로 해석(로컬라이즈 대비), POST엔 API가 준 정확한 name 사용.
- 방향 모호 시 확인 버튼 카드(`ACTION_LINK_CONFIRM`, value `inward|outward|type`)로 사용자가 방향 선택 → SlackInteractionController가 확정.
- `관련/연관` 단독은 오탐 방지 위해 링크로 보지 않음(명시적 연결/링크/relate 필요). 링크 해제는 안내만(Phase 4 예정).
- 실측: 라이브 issueLinkType 조회로 Blocks(outward="blocks")/Duplicate/Relates 방향 확인.

### 특정 이슈 아래 하위작업 생성 — 키/이름 지정 (v0.0.54)

기존엔 하위작업이 **스레드 안에서만** 가능했다(스레드 root 이슈가 부모). 스레드 밖에서
"ES2-123에 하위작업으로 X 추가"라고 하면 Haiku가 register_story로 오분류해 **최상위 스토리**가
생성되던 문제 수정. 결정적(deterministic) 파싱을 라우팅에 추가(프롬프트 무변경 → eval 부담 없음):
- 신규 `util/IssueCommandParser.parseSubtask`: `하위작업 ES2-123 <내용>`(명령형), `ES2-123에 하위작업으로 '..' 추가`(NL 키형),
  `<이름> 스토리 아래에 하위작업 ..`(NL 이름형)을 파싱. 내용은 따옴표 우선, 나머지는 Sonnet이 제목화.
- 이름형은 신규 `JiraApiClient.findIssueKeyByName`(findEpicKeyByName의 매칭 로직 공용화, JQL `issuetype != Epic`)로 키 해석.
- 부모 검증: 없음→안내 / 부모가 서브태스크→거부(서브태스크 아래 서브태스크 불가) / 부모가 에픽→거부(에픽 직속 하위작업 불가, 스토리 연결 안내).
- 라우팅은 에픽 키워드 스테이지보다 **먼저** 실행돼 "에픽 아래 하위작업"을 에픽 생성으로 오인하지 않음. 스레드 안 키 없는 `하위작업 <내용>`은 기존 스레드 경로 유지.

### 에픽 하위 티켓 생성 (v0.0.53)

"'X' 라는 이름으로 `<에픽명>` epic 아래에 task로 생성해줘" 처럼 상위 에픽을 지정한 자연어 생성이
에픽에 연결되지 않던 문제 수정. 원인: `createIssue`/`buildRequest` 에 parent 필드 자체가 없었고
에픽명→키 조회 경로도 없었음. 조치:
- `createIssue(..., parentKey)` 오버로드 + `buildRequest` 에 `fields.parent.key` 세팅(에픽 타입 자신엔 미적용).
  (이 사이트는 company-managed classic 이지만 task→epic 연결에 `parent.key` 를 씀 — 실측 확인.)
- `findEpicKeyByName`: JQL `issuetype = Epic`(L7: 영문 정식명) 로 에픽 목록 조회 후 **정확 일치 우선,
  없으면 양방향 부분 일치**(요약이 추출 문구를 포함/피포함) 로 매칭. 못 찾으면 parent 없이 일반 생성(비치명적).
- `resolveParentEpic`: EPIC_BEFORE(`X epic 아래`) / EPIC_AFTER(`under epic X`) 두 정규식으로 에픽명 추출.
- 실측: "enword DBMS" → **ES2-2141** 매칭 확인. 테스트 2건 추가(연결/미발견 폴백), 전체 370건 통과.

### 분류 재시도 횟수 상향 (v0.0.50)

응답 즉시성보다 정확 분류를 우선(사용자 결정). 두 분류기(Haiku 의도 / Sonnet 상세) 모두 **최대 3회 재시도**
(`MAX_ATTEMPTS=3`)로 통일하고, **타임아웃도 재시도 대상**에 포함(이전엔 Sonnet 은 타임아웃 재시도 안 함).
Haiku 타임아웃 후 재시도는 짧은 타임아웃(15s) 유지로 최악 누적 지연 제한. 분류는 비동기라 Slack 3초 ack 와 무관.

### 버그 원인 자동분류 + 해결 지식 통합 (v0.0.51)

버그가 완료로 전환되면 Notion '버그 현황' DB **한 곳**에 다음을 자동 적재한다:
- **원인분류**(대분류 select) + **세부원인**(소분류 multi_select, 다중) — `prompts/bug-category.md` 체계(10/30)로
  Haiku 분류(`classifyBugCategory`). 소분류 추가는 `prompts/bug-category.md` + `BugCategory.java` 라벨맵만 고치면 됨.
- **근본원인 / 해결방법** — 기존 Claude 요약(`summarizeBugResolution`) 재활용
- **PR링크** — GitHub 이슈키 검색(`searchPullRequestUrls`)으로 해결 PR 링크(클릭 가능)
- 별도 '버그 해결 기록'(resolution) DB 적재 경로는 **제거** → 그 DB 안전하게 삭제 가능 (`.env` `NOTION_DATABASE_ID`도 불필요)
- 모든 enrich 단계는 실패해도 비치명적(해당 속성만 비고 진행). 분류는 비동기라 Slack ack 무관.

### 의도 분류기 타임아웃 재시도 + 상향 (v0.0.49)

v0.0.48 후에도 "보고자가 나인데 완료 안된 task?" 가 "이해하지 못했어요"로 응답. 원인은 exit≠0 이 아니라
**Haiku 호출 타임아웃**(25s) — Haiku 지연은 변동이 커 6~24s+ outlier 가 실측됨. v0.0.48 은 타임아웃을 재시도
안 했기에 즉시 unknown. 조치: (1) 의도분류 타임아웃 25→**40s**, (2) 타임아웃도 **1회 재시도하되 둘째는
짧은 타임아웃(15s)**으로 최악 지연 제한(타임아웃 outlier 는 보통 일시적이라 재시도가 빠르게 성공).

### 의도 분류기에도 재시도 적용 (v0.0.48)

"내가 아직 안한 일이 뭐가 있지?" 같은 정상 입력이 가끔 "이해하지 못했어요"로 응답되던 문제. 원인은 분류 능력이
아니라(직접 분류 시 my_tasks 0.96) **Haiku 의도분류 CLI 의 간헐적 실패 → unknown 으로 즉시 떨어짐**.
v0.0.47 을 의도분류기(`IntentClassifierImpl`)에도 적용: 비-타임아웃 실패는 **1회 재시도**, 타임아웃은 즉시 unknown.
의도분류기 단위 테스트도 신규 추가(이전엔 eval 만 있었음).

### 분류 호출 재시도 + 의도기반 fallback (v0.0.47)

Sonnet 분류 CLI 가 간헐적으로 exit≠0/빈출력/파싱실패로 떨어지던 문제(원문이 제목 되는 fallback 유발)에 대응.
**타임아웃이 아닌 일시적 실패는 1회 재시도**(타임아웃은 지연 2배 방지 위해 재시도 안 함). 두 번 다 실패하면
**Haiku 의도로 type 추정**(register_bug→BUG, register_story→FEATURE)해 OTHER 로 뭉뚱그리지 않는다.
(기존 ES2-2077 은 제목/타입 수동 교정함.)

### 이슈 제목에서 명령어구 제거 (v0.0.46)

"…티켓 만들어줘"처럼 보낸 요청이 **제목에 그대로** 들어가던 문제. 원인은 프롬프트가 아니라(정상 동작),
**Sonnet 분류 호출 실패(예: CLI exit 1) 시 `IssueClassification.fallback()`이 원문을 제목으로 쓰던 것**.
fallback 제목을 정제(앞쪽 이슈키 `[ES2-123]`, 뒤쪽 요청/명령 어구 "만들어줘/등록해줘/추가해줘 …" 제거)하고,
프롬프트(skill + 인라인)에도 "제목에 명령어구·이슈키 금지" 규칙을 명시했다. 성능 영향 없음(실패 경로 한정).
※ Sonnet 호출이 간헐적으로 exit 1 로 실패하는 별개 이슈가 있으며, 이제 실패해도 제목이 깨끗하게 degrade 된다.

### PR 상태별 워크플로 전환 (v0.0.45)

PR-import 가 열린 PR 도 받는다. PR 상태에 따라 전환 목표를 달리한다: **merged → 완료**, **open(ready) → 검토 중**,
**open(draft) → 진행 중**. 어떤 상태든 현재 스프린트로 이동하고, SP 는 생성→(merge 또는 현재) 영업일로 산정한다.
완료가 아니면 `completedAt` 미설정(추이에서 미해결로 집계). (`importMergedPr` → `importPr` 로 이름 변경,
`PullRequestDetail.draft` 추가.)

### 자연어 PR 요청 라우팅 (v0.0.44)

`@지라 <PR URL> 관련 티켓 만들어줘` 처럼 PR URL 이 섞인 자연어가 일반 이슈 생성으로 빠져 문장 전체가 제목이
되던 문제. 메시지에 GitHub PR URL 이 있으면(unfurl `<url|label>` 포함) `pr ` 명령이 아니어도 PR-import 로
라우팅한다. URL 은 정규식으로 추출. (PR-import 는 PR 내용을 읽어 분류/제목/요약 생성 + 기간 기반 SP + 현재
스프린트로 전환.)

### PR 현황 누락 수정 — githubWebClient 버퍼 (v0.0.43)

PR 이 많은 repo(envector-msa, 열린 PR 20개 ≈ 470KB)가 PR 탭에서 통째로 빠지던 버그. githubWebClient 의
기본 인메모리 버퍼(256KB)를 응답이 넘겨 `DataBufferLimitException` → `listOpenPullRequests` 가 빈 목록 반환
(에러 아닌 200 OK 라 inaccessible 로도 안 잡힘). 버퍼 8MB 로 상향(jiraWebClient 와 동일, v0.0.34 와 같은 부류).

### Safari 빈 화면 — 캐시 무력화 + 전역 에러 노출 (v0.0.41)

v0.0.40 후에도 Safari 빈 화면이 지속 보고됨. 두 가지 추가 조치: (1) 정적 자산에 버전 쿼리(`app.js?v=…`)로
**캐시 무력화** — Safari 가 옛 JS 를 들고 있을 가능성 차단. (2) `window.onerror`/`unhandledrejection` +
초기/탭 로더 catch 를 **화면 상단 빨간 배너로 노출** — 100% JS 렌더라 조용히 throw 하면 빈 화면이 되는데,
이제 원인(예: API 401, 미정의 참조)이 화면에 보여 진단 가능. 빈 화면 대신 에러 메시지가 뜬다.

### Safari 대시보드 빈 화면 수정 (v0.0.40)

Safari 에서 대시보드가 통째로 안 보이던 문제 — 대시보드는 100% JS 렌더라 `fmtDate` 가 throw 하면 모든 패널이
빈다. 두 Safari 특이사항을 회피: (1) `Instant` 가 ISO 소수점 **나노초(9자리)** 로 직렬화돼(`...18.672182191Z`)
Safari `new Date` 가 Invalid Date 처리 → 밀리초 3자리로 잘라낸 뒤 파싱. (2) `toLocaleString({dateStyle,timeStyle})`
은 Safari 14.1 미만에서 RangeError → 직접 포맷으로 대체. 날짜 정렬도 동일 정규화(`toDate`) 적용.

### 완료 PR → 티켓 회고 등록 (v0.0.36)

merge된 PR URL 하나로 Jira 티켓을 만들고 현재 스프린트에 완료 상태로 올린다.

- **흐름**: PR 조회(GitHub) → 내용 분석(Claude: BUG/FEATURE/OTHER + 제목/요약) → **PR 생성~merge 영업일**(주말 제외)로
  Story Point 산정(≤0.5→1, ≤1→2, ≤2→3, ≤3→5, >3→8) → 티켓 생성 → 현재 스프린트로 이동 →
  해야 할 일→진행 중→검토 중→완료까지 한번에 전환. 로컬 DB 에 완료일=merge 시각으로 적재(추이 반영).
- **보고자/담당자 = PR 작성자** (v0.0.38): 해결 우선순위 — ① **명시적 GitHub↔Jira 매핑**(`github_user_mappings`,
  v0.0.39) → ② GitHub 프로필 이름으로 Jira user search → ③ 실행자(슬랙) → ④ 토큰 소유자. 해결된 accountId 를
  createIssue 에 넘기면 reporter+assignee 가 모두 그 사람으로 지정된다.
  매핑 관리: `/api/github-mappings` (GET/POST/DELETE — POST 는 `jiraDisplayName`으로 accountId 자동 해석).
  ※ 한글팀처럼 GitHub 이름 ≠ Jira 영문 표시명이라 이름검색이 실패하는 사용자는 이 매핑으로 등록.
- **슬랙**: `@지라 pr <PR URL>`.
- **대시보드**: PR 현황 탭 상단 입력칸 + [PR → 티켓 등록]. `POST /api/dashboard/actions/import-pr {url}`.
- merge 안 된 PR/잘못된 URL/조회 실패는 사유와 함께 거부. PR 출처는 티켓 댓글로 기록.

### 히스토리 백필 + 날짜 파싱 수정 (v0.0.35)

추이/통계가 과거 기록을 못 보여주던 두 원인을 해결:
- **`parseInstant` 버그**: Jira Cloud 의 날짜 오프셋이 `+0900`(콜론 없음)인데 `Instant.parse` 는 `Z` 형식만 받아
  예외 → 동기화로 들어온 모든 이슈의 생성일/완료일이 **null 로 저장**되고 있었다. 콜론 유무 오프셋과 `Z` 를 모두
  파싱하도록 수정 → 이후 동기화부터 날짜가 정상 적재된다.
- **히스토리 부재**: 동기화는 현재 스프린트+백로그만 유지(완료분 prune)하므로 과거가 없다.
  `POST /api/dashboard/actions/backfill-history`(대시보드 추이 탭 **[히스토리 백필]** 버튼)로 Jira 전체 이슈를
  1회 upsert(생성일/완료일 포함)한다. 완료 시각은 `resolutiondate`(비면 `statusCategoryChangedDate`) 사용.
- **완료 이슈 보존**: 백로그 prune 이 `completedAt IS NOT NULL` 이슈는 삭제하지 않게 변경 → 백필분과 이후 완료분이
  히스토리로 누적된다.

### 응답 시간 계측 (v0.0.29)

이슈 생성 응답이 느릴 때 원인 구간을 바로 짚을 수 있도록 매 건 계측한다.

- **DB 적재**: `response_metrics` 테이블 (Flyway V2) — Slack 메시지 ts 기준 **end-to-end total_ms** +
  Spring 내부 단계별(`classify`/`duplicate`/`jira`/`db`/`notify`) ms, 실패 건도 errorType 과 함께 기록.
  total 과 단계 합의 차이 ≈ Haiku 의도분류 + Go봇/터널 전달 + async 큐 대기 (Spring 밖 구간).
- **Slack 표기**: 이슈/에픽 생성 메시지 맨 끝에 `⏱ 응답 시간 N.N초` context 라인.
- **대시보드**: 봇 상태 탭에 7일 통계 카드(건수/평균/p50/p95/최대) + 최근 50건 단계별 테이블
  (`GET /api/dashboard/response-metrics`).

### 버그 수정 (v0.0.28)

- Slack 발 이슈 생성 시 DB `issues.reporter` 에 Slack ID 가 저장되던 버그 수정 — Jira displayName 으로 저장
  (할당 DM 의 `reporter:` 라인에 raw Slack ID 가 노출되던 원인).

### Claude CLI 최적화 (v0.0.22)

- **프롬프트 skill 파일 외부화**: 분류/검색/해결요약/브랜치슬러그 시스템 프롬프트를 디스크 `prompts/*.md` 로 분리하고
  `--system-prompt-file` 로 전달 (headless 권장 패턴, stdin 은 순수 사용자 입력만). 프롬프트 수정에 재빌드 불필요,
  파일 없으면 기존 인라인 방식으로 자동 폴백.
- **모델 계층화**: 브랜치 슬러그 같은 단순 변환은 `claude.fast-model`(기본 Haiku 4.5)로 — Sonnet 대비 수 초 단축.
  품질이 중요한 이슈 분류·시맨틱 검색·버그 해결 요약은 Sonnet 유지.
- **검색 컨텍스트 상한**: 시맨틱 검색이 Claude 에게 보내는 이슈 목록을 최근 갱신순 150건으로 제한 — 토큰/지연 절감.

## 아키텍처

```
Slack 메시지 → ngrok → Go Bot(:3000) → Spring Boot(:8080)
    → SlackSignatureFilter (HMAC 검증)
    → 키워드 매칭 (help/scrum/내작업/sync/완료/작업)
    → Haiku 의도 분류 (register_bug/register_story/search/...)
    → Sonnet 상세 분류 (제목/SP/타입)
    → Jira API (이슈 생성) + PostgreSQL (로컬 저장)
    → Slack 스레드 알림 (Block Kit + 인터랙티브 버튼)
    → 버튼 클릭 → Go Bot(/slack/interactions) → Spring Boot(/api/slack/interaction)
    → Jira 상태 전환 + 메시지 업데이트
```

## 사전 조건

| 항목 | 버전 |
|---|---|
| Java | 17+ |
| Go | 1.25+ |
| Docker | 28+ |
| ngrok | 3+ |
| Claude CLI | 2.1+ (`claude login` 완료) |

## 빠른 시작

### 1. 환경 변수 설정

`.env` 파일을 프로젝트 루트에 생성합니다:

```bash
# Slack
SLACK_SIGNING_SECRET=<Slack App Basic Information에서 복사>
SLACK_BOT_TOKEN=<xoxb-로 시작하는 Bot User OAuth Token>
SLACK_ALLOWED_CHANNELS=<허용 채널 ID 쉼표 구분>

# Jira
JIRA_BASE_URL=<https://your-site.atlassian.net>
JIRA_EMAIL=<Atlassian 계정 이메일>
JIRA_API_TOKEN=<Atlassian API Token>
JIRA_PROJECT_KEY=<프로젝트 키 (예: PROJ)>

# Postgres
POSTGRES_DB=jirabot
POSTGRES_USER=jirabot
POSTGRES_PASSWORD=<임의 비밀번호>

# GitHub 브랜치 생성 (선택) — 비우면 "진행 중" 시 Jira 개발 패널 링크 안내로 폴백
GITHUB_BRANCH_TOKEN=<fine-grained PAT, 대상 repo Contents: Read & Write>
GITHUB_ORG=<조직 (기본 CryptoLabInc)>
GITHUB_BRANCH_REPOS=<버튼에 띄울 repo, 콤마 구분 (기본 envector-msa,evi)>

# 터널 경유 대시보드 접근 (선택, v0.0.30) — 둘 다 설정해야 Go봇 프록시가 켜짐
DASHBOARD_USER=<대시보드 Basic Auth 아이디>
DASHBOARD_PASSWORD=<대시보드 Basic Auth 비밀번호 (강한 랜덤값)>

# 기능요청 게시판 (선택, v0.0.32) — 새 글 등록 시 DM 받을 Slack user ID (비우면 DM 생략)
FEATURE_REQUEST_NOTIFY_USER=<관리자 Slack user ID (예: U03L1TJ0EBB)>
```

> **GitHub 토큰 발급**: GitHub → Settings → Developer settings → Fine-grained tokens → Resource owner=조직, 대상 repo 선택, Repository permissions의 **Contents: Read and write**. 발급한 토큰을 `GITHUB_BRANCH_TOKEN`에 넣으면 "진행 중" 전환 시 repo 선택 버튼이 활성화됩니다.

### 2-A. 서비스 기동 — Docker Compose (권장, v0.0.25)

```bash
# 사전 조건: .env 작성 + 호스트에서 claude login (CLI 인증을 컨테이너 볼륨으로 재사용)
docker-compose --profile full up -d --build   # Postgres + Spring(:8080) + Go봇(:3000)
ngrok http 3000                                # 터널만 호스트에서
```

- `--profile full` 없이 `up -d` 하면 **Postgres만** 기동 (bare-metal 운영 호스트와 포트 충돌 방지용 안전장치).
- 필수 환경변수(SLACK_BOT_TOKEN, SLACK_SIGNING_SECRET, JIRA_BASE_URL/EMAIL/API_TOKEN/PROJECT_KEY)가 비어 있으면
  서버가 **기동 시점에 누락 키 목록과 함께 즉시 실패**합니다 (fail-fast — 모호한 첫-호출 실패 대신).
- bare-metal 운영 로그 로테이션: `sudo cp ops/logrotate-slackbot /etc/logrotate.d/slackbot` (일 1회, 7일 보관).

### 2-B. 서비스 기동 — 수동 (4개 터미널)

```bash
# Terminal 1: PostgreSQL
docker compose up -d postgres

# Terminal 2: Spring Boot (:8080)
set -a && source .env && set +a
./gradlew bootRun

# Terminal 3: Go Bot (:3000)
cd bot && set -a && source ../.env && set +a
go run .

# Terminal 4: ngrok
ngrok http 3000
```

### 3. Slack 설정

1. https://api.slack.com/apps → 앱 선택
2. **Event Subscriptions** → Enable Events → ON
3. **Request URL**: `https://<ngrok-url>/slack/events` (Verified 확인)
4. **Subscribe to bot events**: `app_mention`
5. Save Changes
6. **Interactivity & Shortcuts** → Interactivity → ON
7. **Request URL**: `https://<ngrok-url>/slack/interactions`
8. Save Changes

## 사용법

### 키워드 명령 (즉시 실행)

| 명령 | 설명 |
|---|---|
| `@지라 help` | 도움말 표시 |
| `@지라 안녕` | 인사 + 사용법 안내 (안녕/하이/hi/hello 등) |
| `@지라 scrum` | 스프린트 일일 리포트 |
| `@지라 통계` | 활성 스프린트 통계(담당자/상태별, SP 집계) |
| `@지라 내작업` | 내 진행 중인 작업 조회 |
| `@지라 작업 김영현` | 특정 팀원의 작업 조회 |
| `@지라 검색 <키워드>` | 이슈 제목·설명 의미 검색 (모든 상태 포함) |
| `@지라 ES2-123` | 이슈 키로 상세 카드 조회 (상태 전환 버튼 포함, `ES2-123 보여줘` 같은 접미어 허용) |
| `@지라 할당 ES2-123 홍길동` | 이슈 담당자 지정 (`@멘션` 도 가능 — 등록된 사용자만) |
| `@지라 할당알림 on` / `off` / `상태` | 본인 할당 시 DM 알림 토글 (기본 ON) |
| `@지라 버그` / `@지라 버그 YYYY.MM.DD` | 해결된 버그 조회(트러블슈팅) |
| `@지라 등록 <Jira 사용자명>` | 본인 Slack↔Jira 매핑 등록 |
| `@지라 리마인더 on` / `off` / `상태` | 미해결 이슈 DM 리마인더 토글 |
| `@지라 notion백필` | 전체 버그를 Notion '버그 현황' DB에 동기화 |
| `@지라 sync` | Jira → DB 수동 동기화 |
| `@지라 완료` | 이슈 스레드에서 → Jira 완료 처리 |

### 자연어 입력 (AI 분류 → Jira 이슈 생성)

```
@지라 로그인 페이지에서 500 에러 발생     → 버그로 등록
@지라 다크모드 지원해주세요               → 기능 요청으로 등록
```

AI가 자동으로 분류(BUG/FEATURE/OTHER), 제목, Story Point를 추정합니다.

### 리마인더 (opt-in)

미해결 이슈를 DM으로 알려주는 기능. 먼저 `@지라 등록 <Jira 사용자명>` 으로 매핑을 만든 뒤 켭니다.

| 명령 | 설명 |
|---|---|
| `@지라 리마인더 on` | 켜기 |
| `@지라 리마인더 off` | 끄기 |
| `@지라 리마인더 상태` | 현재 ON/OFF·스케줄 확인 (`status` 도 가능) |

- **일일 리마인더** — 평일 **09:00 KST**, **현재 활성 스프린트**의 미해결 이슈만. "진행 중"으로 `reminder.stale-days`(기본 7일) 이상 정체된 이슈는 ⚠️ + 경과 일수로 태그(진입 시각 `inProgressSince` 기준).
- **격주 리마인더** — **월요일 09:30 KST**, **전체 미해결 이슈**(스프린트+백로그). `reminder.biweekly-anchor`(기본 `2026-06-22`)를 기준으로 **격주(짝수 주차)** 월요일에만 발송.
- 중복 방지: 격주 리마인더가 나가는 월요일에는 그날의 일일 리마인더를 생략합니다.
- **소유 기준**: 이슈 담당자(assignee)에게 귀속, 담당자가 없으면 **보고자(reporter)** 에게 귀속.
- 미해결 0건인 사용자에게는 DM을 보내지 않습니다.
- 설정: `reminder.cron`(일일), `reminder.biweekly-cron`(격주 점화), `reminder.biweekly-anchor`(격주 기준 월요일), `reminder.stale-days`(정체 임계, 기본 7), `reminder.zone`, `reminder.enabled`(전역 차단).

### 에픽 생성 (`에픽`/`epic` 키워드)

```
@지라 에픽 GCP marketplace 배포 확장      → Epic으로 등록 (SP 없음)
@지라 create epic for billing revamp     → Epic으로 등록
```

메시지에 `에픽` 또는 `epic` 이 단어로 포함되면 **AI 분류를 거치지 않고 항상 Epic으로 생성**됩니다.
에픽은 컨테이너성 이슈라 Story Point를 부여하지 않으며, 스프린트 워크플로 버튼(해야 할 일/진행 중)도
표시하지 않아 일반 스토리/버그와 시각적으로 구별됩니다. Jira 이슈타입명은 `JIRA_ISSUE_TYPE_EPIC` 로 설정
(ES2는 `에픽`).

### 스레드 액션 (이슈 생성 스레드에서 댓글로 사용)

| 명령 | 설명 |
|---|---|
| `@지라 하위작업 <내용>` | 하위작업 생성 (Sonnet이 제목/SP 추정) |
| `@지라 댓글 <내용>` | Jira 이슈에 코멘트 추가 |
| `@지라 수정 <내용>` | Jira 설명에 내용 추가 (append) |
| `@지라 담당자 <이름>` | 이 스레드 이슈의 담당자 지정 (응답에 대상 이슈 키 명시) |
| `@지라 완료` | Jira 상태 완료로 전환 |
| 자연어 입력 | AI가 액션 자동 판단 (하위작업/댓글/수정) |

### 사용 예시

```
[채널]
나: @지라 결제 완료 후 금액이 0원으로 표시됩니다
봇: ✅ Jira 이슈가 등록되었습니다!
    [SLAC-15] 결제 금액 0원 표시 버그
    분류: BUG | Story Point: 5
    [🔨 진행 중]  [✅ 완료]    ← 인터랙티브 버튼

    [버튼 클릭]
    봇: 🔧 SLAC-15 → 진행 중 (by 김영현)

    [스레드에서]
    나: @지라 하위작업 프론트엔드 금액 표시 로직 수정
    봇: ✅ 하위작업 생성: SLAC-16 (상위: SLAC-15)

    나: @지라 댓글 재현 조건: 카드 결제만 해당
    봇: 💬 SLAC-15에 코멘트가 추가되었습니다.

    나: @지라 완료
    봇: ✅ SLAC-15 → 완료 처리되었습니다.
```

## 스크립트

### Jira 프로젝트 변경

다른 Jira 사이트/프로젝트로 전환할 때 사용합니다.

```bash
# 현재 설정 확인
./scripts/switch-jira-project.sh --show

# 대화형 변경 (URL, 이메일, 토큰, 프로젝트 키 입력)
./scripts/switch-jira-project.sh

# 직접 지정
./scripts/switch-jira-project.sh \
  --url https://company.atlassian.net \
  --email you@company.com \
  --token ATATT3x... \
  --project PROJ
```

변경 후 Spring Boot 재시작 + `@지라 sync` 필요.

### 유저 매핑 등록

Slack 이름과 Jira 이름이 다를 때 매핑을 등록합니다. 
등록하지 않으면 Slack API에서 실명을 자동 조회하여 매핑합니다.

```bash
# 대화형 등록
./scripts/register-user-mapping.sh

# 직접 등록
./scripts/register-user-mapping.sh U03L1TJ0EBB 김영현

# 등록된 매핑 목록 조회
./scripts/register-user-mapping.sh --list
```

Slack 유저 ID는 Slack에서 유저 프로필 → 더보기(⋯) → 멤버 ID 복사로 확인합니다.

## 테스트

```bash
./gradlew test        # Spring Boot 단위 테스트
cd bot && go test ./...  # Go Bot 테스트
```

## 기술 스택

| 컴포넌트 | 기술 |
|---|---|
| Spring Boot | 3.5, Java 17, Gradle |
| Go Bot | Go 1.25+, slack-go SDK |
| DB | PostgreSQL 16 (Docker) |
| AI 분류 | Claude CLI (Haiku: 의도 분류, Sonnet: 상세 분류) |
| 보안 | HMAC-SHA256 서명 검증, 채널 제한 |

## 프로젝트 구조

```
slackbot/
├── src/main/java/com/jirabot/slack/
│   ├── controller/     # SlackEventController, SlackInteractionController, HealthController, UserMappingController
│   ├── service/        # IssueCreateService, ScrumReportService, JiraSyncService, DuplicateDetectionService
│   ├── client/         # ClaudeApiClient, JiraApiClient, IntentClassifier, ThreadActionClassifier, SlackNotifier
│   ├── entity/         # IssueEntity, IntentFailureEntity, UserMappingEntity
│   ├── repository/     # JPA Repositories
│   ├── config/         # SecurityConfig, AsyncConfig, WebClientConfig, Properties
│   ├── filter/         # SlackSignatureFilter, CachedBodyFilter
│   └── dto/            # IssueCreateCommand, SlackEventEnvelope, SlackEventInner
├── bot/                # Go Slack Bot (프록시)
├── prompts/            # AI 프롬프트 파일
│   ├── haiku-classifier.md       # Haiku 의도 분류 프롬프트
│   └── haiku-thread-action.md    # Haiku 스레드 액션 분류 프롬프트
├── scripts/
│   ├── switch-jira-project.sh    # Jira 프로젝트 변경
│   └── register-user-mapping.sh  # 유저 매핑 등록
├── docs/
│   └── how-to-run.md             # 상세 실행 가이드
├── docker-compose.yml            # PostgreSQL
└── .env                          # 환경 변수 (gitignored)
```

## 트러블슈팅

| 증상 | 해결 |
|---|---|
| Docker `keychain` 에러 | `~/.docker/config.json`에서 `"credsStore": ""` 변경 |
| 포트 5000 충돌 (macOS) | AirPlay Receiver가 점유. 시스템 설정에서 끄기 |
| ngrok URL 변경 후 Slack 안 됨 | Slack Event Subscriptions에서 새 URL로 재등록 |
| Claude CLI 인증 만료 | `claude login` 재실행 |
| "이해하지 못했어요" 반복 | `intent_failures` 테이블 확인 (`docker exec jirabot-postgres psql -U jirabot -d jirabot -c "SELECT * FROM intent_failures ORDER BY failed_at DESC LIMIT 10;"`) |
| 봇이 특정 채널에서 무응답 | `.env`의 `SLACK_ALLOWED_CHANNELS`에 해당 채널 ID 추가 |
| `column ... does not exist` (예: `reminder_enabled`) | `ddl-auto=update`가 데이터 있는 테이블에 `NOT NULL` 컬럼을 DEFAULT 없이 ADD 하다 실패 → Hibernate가 WARN만 남기고 기동(컬럼 미생성). 엔티티에 `@Column(columnDefinition="... default ...")` 명시. 기존 DB는 `ALTER TABLE <t> ADD COLUMN IF NOT EXISTS <c> <type> NOT NULL DEFAULT <v>;` 수동 적용 |
