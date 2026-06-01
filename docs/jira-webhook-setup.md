# Jira Webhook 설정 가이드

Jira에서 이슈 상태/담당자가 변경되면 봇이 Slack 스레드에 실시간 알림을 보내는 기능입니다.

## 구조

```
Jira Cloud (이슈 변경 발생)
  → POST https://<ngrok-url>/api/jira/webhook?token=<secret>
    → Go bot (port 3000) → Spring Boot (port 8080)
      → Slack 스레드에 알림
```

## 1. 환경 변수 추가

`.env` 파일에 아래 항목을 추가합니다:

```bash
# Jira Webhook
JIRA_WEBHOOK_ENABLED=true
JIRA_WEBHOOK_SECRET=<임의의 비밀 토큰>  # 예: openssl rand -hex 32 로 생성

# 알림 트리거 모드 (택 1)
#   STATUS              - 모든 상태 변경
#   STATUS_CATEGORY     - 카테고리 변경만 (해야할일→진행중)
#   DONE_ONLY           - 완료 전환만
#   STATUS_AND_ASSIGNEE - 상태 또는 담당자 변경 (기본값)
JIRA_WEBHOOK_NOTIFY_ON=STATUS_AND_ASSIGNEE

# 멘션 모드
#   MENTION - <@USER> 형식 (Slack 알림 발생)
#   PLAIN   - 이름만 표기 (알림 없음)
NOTIFY_MENTION=MENTION
```

secret 생성 예시:
```bash
openssl rand -hex 32
# 출력 예: a3f8c1d4e5b6...
```

## 2. Jira Webhook 등록

### 접속
`https://<사이트>.atlassian.net/plugins/servlet/webhooks`

또는: Jira 설정 (톱니바퀴) → 시스템 → Webhook

### 설정

| 항목 | 값 |
|------|-----|
| Name | `Slack Bot Notification` (자유) |
| Status | Enabled |
| URL | `https://<ngrok-도메인>/api/jira/webhook?token=<JIRA_WEBHOOK_SECRET>` |
| Events | `Issue > updated` 만 선택 |

### URL 예시
```
https://tidiness-pointed-amuser.ngrok-free.dev/api/jira/webhook?token=a3f8c1d4e5b6...
```

### 프로젝트 필터 (선택)
- "Issue related events" 아래 JQL 필터로 특정 프로젝트만 지정 가능
- 예: `project = ES2`

## 3. Go bot 라우팅 추가

현재 Go bot은 `/slack/events`와 `/slack/interactions`만 Spring Boot로 전달합니다.
Jira webhook은 ngrok → Go bot(3000) → Spring Boot(8080) 경로를 타야 하므로
Go bot에 `/api/jira/` 라우팅을 추가해야 합니다.

`bot/main.go`에 추가:
```go
// Jira webhook → Spring Boot 직접 전달
jiraForwarder := NewForwarder(cfg.SpringJiraWebhookURL, &http.Client{Timeout: cfg.ForwardTimeout}, logger)
mux.Handle("/api/jira/", NewJiraWebhookHandler(jiraForwarder, logger))
```

`bot/config.go`에 추가:
```go
SpringJiraWebhookURL string // 예: http://localhost:8080/api/jira/webhook
```

또는 간단하게 Go bot의 mux에 pass-through 핸들러를 등록하여
`/api/jira/webhook` 요청을 `http://localhost:8080/api/jira/webhook`으로 그대로 전달합니다.

## 4. 동작 확인

1. 서버 시작 (`./start.sh`)
2. Jira에서 봇이 생성한 이슈의 상태를 변경
3. 해당 이슈의 Slack 스레드에 알림이 오는지 확인

### 정상 로그 예시
```
INFO  JiraWebhookServiceImpl : Webhook notified key=ES2-100 changelogId=12345
```

### 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| 403 응답 | token 불일치 또는 비어있음 | `.env`의 `JIRA_WEBHOOK_SECRET`과 Jira URL의 token 일치 확인 |
| 요청 안 옴 | Go bot이 `/api/jira/` 미전달 | `forward.go`에 경로 추가 |
| 알림 안 옴 | 봇 생성 이슈가 아님 | Slack 스레드가 있는 이슈만 알림 대상 |
| 중복 알림 | Jira 재전송 | `processed_jira_changelog` 테이블이 자동 차단 |

## 알림 메시지 예시

```
🔄 ES2-100 로그인 에러
상태: 해야 할 일 → 진행 중
reporter: Alice
변경자: @Bob
```
