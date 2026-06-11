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

### 스키마 마이그레이션 — Flyway (v0.0.24)

- 스키마는 **`src/main/resources/db/migration/V<N>__*.sql` 마이그레이션으로만 변경**한다. `ddl-auto=validate` 라
  엔티티만 고치면 기동이 실패한다(의도된 동작 — 조용한 드리프트 차단).
- 컬럼/테이블 추가 절차: ① `V2__add_xxx.sql` 작성 ② 엔티티 동기 수정 ③ 테스트 ④ 배포(기동 시 자동 적용).
- 기존 운영 DB 는 `baseline-on-migrate` 로 V1(2026-06-11 스냅샷)이 적용된 것으로 처리됐고,
  신규(빈) DB 는 V1 부터 실행되어 전체 스키마가 만들어진다 (신규 설치 경로 검증 완료).
- 테스트(H2)는 Flyway 를 끄고 기존 create-drop 유지 (`application-test.yml`).
- 백업: 전환 직전 풀 백업 `~/backups/jirabot-pre-flyway-*.dump` (복원: `pg_restore -U jirabot -d jirabot <dump>`).

### Jira 웹훅 수신 경로 (v0.0.23)

Jira → ngrok 터널(:3000) → **Go 봇 `/api/jira/webhook` 프록시** → Spring `/api/jira/webhook`.
ngrok 이 Go 봇만 노출하므로 프록시가 필수다. 등록은 Jira **사이트 관리자**가 설정 → 시스템 → 웹훅에서:

- URL: `https://<ngrok-domain>/api/jira/webhook?token=<JIRA_WEBHOOK_SECRET>`
- 이벤트: 이슈 → **업데이트됨** (`jira:issue_updated`)
- JQL 필터: `project = ES2`

등록이 없으면 할당 DM·스레드 상태변경 알림·버그 완료 Notion 자동 동기화가 모두 동작하지 않는다.
수신/할당 DM 판정(발송·생략 사유)은 INFO 로그로 남는다 (`Jira webhook received`, `Assign DM sent/skipped`).

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
