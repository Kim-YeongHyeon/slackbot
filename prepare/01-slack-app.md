# 01. Slack App 생성 & 권한 설정

## 최종 산출물

`.env` 에 넣을 값 2개:

```env
SLACK_SIGNING_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
SLACK_BOT_TOKEN=xoxb-...
```

그리고 Slack Workspace 에 "봇이 설치된 채널" 1개.

## 단계

### 1) App 생성

1. https://api.slack.com/apps → **Create New App** → **From scratch**
2. App Name: `JiraBot` (예시, 자유)
3. Workspace: 본인 워크스페이스 선택

### 2) Signing Secret 복사

- **Basic Information** → **App Credentials** → **Signing Secret** → Show → 복사
- 이 값이 `SLACK_SIGNING_SECRET`. HMAC-SHA256 서명 검증에 사용됨.

### 3) Bot Token Scopes 추가

- **OAuth & Permissions** → **Scopes** → **Bot Token Scopes** → Add:
  - `app_mentions:read` — 멘션 이벤트 수신
  - `chat:write` — (Phase 2 이후 응답 전송 시 필요. 지금 넣어두면 편함)
  - `channels:history` — (옵션) 채널 메시지 읽기
  - `im:history` — (옵션) DM 메시지 읽기
  - `reactions:write` — (옵션, v0.0.91) CI 알림 처리 상태 리액션(👀 ✅ ⚠️)

### 4) 워크스페이스에 설치 & Bot Token 복사

- **OAuth & Permissions** → **Install to Workspace** → Allow
- 설치 후 상단에 표시되는 **Bot User OAuth Token** (`xoxb-...`) 복사
- 이 값이 `SLACK_BOT_TOKEN`.

### 5) Event Subscriptions — **이 단계는 공개 URL(ngrok 등) 이 먼저 준비되어야 함**

`04-public-url.md` 먼저 보고 돌아오세요.

- **Event Subscriptions** → **Enable Events: On**
- **Request URL**: `https://<ngrok-subdomain>.ngrok-free.app/slack/events`
  - (Go bot 이 `:3000` 에서 이 경로로 받고 Spring `:8080/api/slack/event` 로 포워드)
  - Slack 이 `url_verification` 챌린지를 보내서 Spring 이 `challenge` 값을 echo 해야 녹색 ✓ 나옴.
  - Spring + Go bot 이 모두 켜진 상태여야 검증 통과.
- **Subscribe to bot events** → Add:
  - `app_mention` — 채널에서 봇 멘션 시 이벤트 수신
  - `message.channels` — (옵션, v0.0.91) 알림 채널의 CI·SBOM 실패 알림을 멘션 없이 받기 (`channels:history` 필요)
- **Save Changes**

### 6) 테스트 채널에 봇 초대

```
/invite @JiraBot
```

또는 채널 설정 → Integrations → Add apps.

## 트러블슈팅

- **Request URL 검증 실패**: Spring 이 url_verification 응답을 못 냄. `curl` 로 직접 확인:
  ```bash
  curl -X POST http://localhost:8080/api/slack/event \
    -H 'Content-Type: application/json' \
    -d '{"type":"url_verification","challenge":"abc"}'
  # → {"challenge":"abc"} 기대
  ```
  (이 경로는 서명 필터를 탑니다 — 실제 Slack 요청에는 서명 헤더가 붙어서 옵니다.
   `url_verification` 만 로컬 테스트하려면 임시로 필터 제외를 적용하거나 ngrok 통해 Slack 에서 직접 시도.)

- **403 Forbidden**: HMAC 서명 불일치. `SLACK_SIGNING_SECRET` 이 `.env` 와 Slack App 이 동일한지 확인.

- **재전송 폭주**: 봇이 3초 안에 200 을 못 돌려주면 Slack 이 동일 이벤트를 최대 3회 재전송. Go bot 단계에서 즉시 ack, Spring 은 `@Async` 로 처리되므로 정상 구성 시 1회만 도착해야 함. 중복 이슈 생성되면 `event_id` 기반 idempotency 추가 검토 필요(Phase 2).
