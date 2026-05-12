# Jira Slack Bot

Slack 채널에서 자연어로 메시지를 보내면 AI가 자동 분류하여 Jira 이슈를 생성하는 봇.

## 주요 기능

| 기능 | 설명 |
|---|---|
| AI 이슈 자동 생성 | 자연어 → Haiku 의도 분류 → Sonnet 상세화(제목/SP/타입) → Jira 등록 |
| 스프린트 리포트 | 담당자별 진행 상황, SP 집계 |
| 작업 조회 | 내 작업 / 팀원 작업 조회 |
| 스레드 액션 | 이슈 스레드에서 하위작업 생성, 댓글 추가, 설명 수정, 완료 처리 |
| 중복 감지 | 이슈 생성 시 DB에서 유사 이슈 검색 후 경고 |
| 이슈 검색 | 키워드로 이슈 제목 검색 (모든 상태 포함) |
| 채널 제한 | 허용된 채널에서만 봇 동작 |

## 아키텍처

```
Slack 메시지 → ngrok → Go Bot(:3000) → Spring Boot(:8080)
    → SlackSignatureFilter (HMAC 검증)
    → 키워드 매칭 (help/scrum/내작업/sync/완료/작업)
    → Haiku 의도 분류 (register_bug/register_story/search/...)
    → Sonnet 상세 분류 (제목/SP/타입)
    → Jira API (이슈 생성) + PostgreSQL (로컬 저장)
    → Slack 스레드 알림
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
```

### 2. 서비스 기동 (4개 터미널)

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

## 사용법

### 키워드 명령 (즉시 실행)

| 명령 | 설명 |
|---|---|
| `@지라 help` | 도움말 표시 |
| `@지라 scrum` | 스프린트 일일 리포트 |
| `@지라 내작업` | 내 진행 중인 작업 조회 |
| `@지라 작업 김영현` | 특정 팀원의 작업 조회 |
| `@지라 검색 <키워드>` | 이슈 제목으로 검색 |
| `@지라 sync` | Jira → DB 수동 동기화 |
| `@지라 완료` | 이슈 스레드에서 → Jira 완료 처리 |

### 자연어 입력 (AI 분류 → Jira 이슈 생성)

```
@지라 로그인 페이지에서 500 에러 발생     → 버그로 등록
@지라 다크모드 지원해주세요               → 기능 요청으로 등록
```

AI가 자동으로 분류(BUG/FEATURE/OTHER), 제목, Story Point를 추정합니다.

### 스레드 액션 (이슈 생성 스레드에서 댓글로 사용)

| 명령 | 설명 |
|---|---|
| `@지라 하위작업 <내용>` | 하위작업 생성 (Sonnet이 제목/SP 추정) |
| `@지라 댓글 <내용>` | Jira 이슈에 코멘트 추가 |
| `@지라 수정 <내용>` | Jira 설명에 내용 추가 (append) |
| `@지라 완료` | Jira 상태 완료로 전환 |
| 자연어 입력 | AI가 액션 자동 판단 (하위작업/댓글/수정) |

### 사용 예시

```
[채널]
나: @지라 결제 완료 후 금액이 0원으로 표시됩니다
봇: ✅ Jira 이슈가 등록되었습니다!
    [SLAC-15] 결제 금액 0원 표시 버그
    분류: BUG | Story Point: 5

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
│   ├── controller/     # SlackEventController, HealthController, UserMappingController
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
