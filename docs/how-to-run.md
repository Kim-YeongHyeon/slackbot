# 실행 방법

## 사전 조건

| 항목 | 버전 | 확인 명령 |
|---|---|---|
| Java | 17+ | `java -version` |
| Go | 1.25+ | `go version` |
| Docker | 28+ | `docker --version` |
| ngrok | 3+ | `ngrok version` |
| Claude CLI | 2.1+ | `claude --version` |

## 1. 환경 변수 설정

`.env` 파일이 프로젝트 루트에 있어야 합니다:

```bash
# Slack
SLACK_SIGNING_SECRET=<Slack App 설정에서 복사>
SLACK_BOT_TOKEN=xoxb-<Slack App 설치 후 발급>

# Jira
JIRA_BASE_URL=https://<사이트>.atlassian.net
JIRA_EMAIL=<Atlassian 계정 이메일>
JIRA_API_TOKEN=<Atlassian API Token>
JIRA_PROJECT_KEY=<프로젝트 키 (예: SLAC)>

# Postgres
POSTGRES_DB=jirabot
POSTGRES_USER=jirabot
POSTGRES_PASSWORD=<임의 비밀번호 (예: jirabot-local)>
```

## 2. 서비스 기동 (4단계)

### Terminal 1: PostgreSQL

```bash
docker compose up -d postgres
docker compose logs -f postgres  # "ready to accept connections" 확인
```

### Terminal 2: Spring Boot (:8080)

```bash
set -a && source .env && set +a
./gradlew bootRun
```

헬스체크: `curl http://localhost:8080/health`

### Terminal 3: Go Bot (:3000)

```bash
cd bot
set -a && source ../.env && set +a
go run .
```

헬스체크: `curl http://localhost:3000/health`

### Terminal 4: ngrok

```bash
ngrok http 3000
```

출력된 `https://xxx.ngrok-free.dev` URL을 복사합니다.

## 3. Slack 설정

1. https://api.slack.com/apps 에서 앱 선택
2. **Event Subscriptions** > Enable Events > ON
3. **Request URL**: `https://<ngrok-url>/slack/events`
4. Slack이 Verified 확인
5. **Subscribe to bot events**: `app_mention` 만 추가
6. Save Changes

## 4. 사용법

| 명령 | 설명 |
|---|---|
| `@지라 help` | 도움말 표시 |
| `@지라 scrum` | 스프린트 일일 리포트 |
| `@지라 내작업` | 내 진행 중인 작업 조회 |
| `@지라 작업 김영현` | 특정 팀원의 작업 조회 |
| `@지라 <자연어>` | AI 분류 후 Jira 이슈 자동 생성 |

## 5. 종료

```bash
# Spring Boot: Terminal 2에서 Ctrl+C
# Go Bot: Terminal 3에서 Ctrl+C
# ngrok: Terminal 4에서 Ctrl+C
# Postgres:
docker compose down
```

## 6. 테스트

```bash
./gradlew test        # Spring Boot 단위 테스트
cd bot && go test ./...  # Go Bot 테스트
```

## 트러블슈팅

| 증상 | 해결 |
|---|---|
| Docker `keychain` 에러 | `~/.docker/config.json`에서 `"credsStore": ""` 로 변경 |
| 포트 5000 충돌 (macOS) | AirPlay Receiver가 점유. 시스템 설정에서 끄거나 다른 포트 사용 |
| ngrok URL 변경 후 Slack 안 됨 | Slack Event Subscriptions에서 새 URL로 재등록 |
| Claude CLI 인증 만료 | `claude login` 재실행 |
