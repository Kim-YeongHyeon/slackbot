# Deferred — 버그 완료 시 Notion 자동 정리

> **등록일:** 2026-04-23
> **우선순위:** Phase 4 후보
> **요청자:** 사용자
> **상태(2026-06-01):** ✅ **구현됨 (v0.0.5)**

## ✅ 구현된 것 (2026-06-01)

두 개의 Notion DB(부모 페이지 `NOTION_PARENT_PAGE_ID` 하위):
- **버그 해결 기록**(`NOTION_DATABASE_ID`): 이슈/원인/해결방법/해결일/담당자/Jira링크.
  버그가 완료로 전환되면 Jira 설명+댓글+Slack 스레드를 Claude 가 요약(`summarizeBugResolution`)해 적재.
- **버그 현황**(`NOTION_STATUS_DB_ID`): 이슈/상태(해결·미해결)/Jira상태/담당자/생성일/해결일/Jira링크.
  전체 버그를 동기화. `@지라 notion백필` 또는 웹훅 상태 변경 시 upsert(이슈키 기준).

트리거: **Jira 웹훅(상태→완료)** — 버튼/명령/Jira 직접수정 모두 커버. 초기 백필로 기존 104건 적재(해결 74/미해결 30).

구현: `NotionApiClient(+Impl)`, `util/NotionProperty`, `BugNotionService(+Impl)`,
`ClaudeApiClient.summarizeBugResolution`, `JiraApiClient.searchByJql/getComments`,
`JiraWebhookServiceImpl` 분기, `SlackEventController` `notion백필` 명령, `config/NotionProperties`.

### 함정/교훈 (L4 심화)
- JQL `issuetype = "버그"`(표시명)는 **0건** — JQL 정식 이름은 `Bug`/id(10034). 응답의 issuetype.name 은 "버그".
  → 백필은 issuetype JQL 필터 대신 **프로젝트 전체 조회 후 표시명으로 클라이언트 필터**.

### 남은(선택) 고도화
- 과거 해결 버그의 원인/해결 요약 백필(현재는 현황만 백필, 요약은 완료 시점부터).
- 웹훅은 봇 추적(스레드 보유) 버그만 자동 동기화 → Jira 직접 생성 버그는 `notion백필` 로 반영.

---

### (이하 최초 설계안 — 참고용)

## 요구사항

버그 이슈가 "완료"로 전환되면, 해당 버그의 원인과 해결 방법을 특정 Notion 페이지에 자동으로 정리한다.

## 예시 흐름

```
@지라 완료 (스레드에서)
    ↓
Jira 상태 → 완료 전환
    ↓
Claude에게 Jira 이슈 내용 전달 → 원인/해결방법 요약 생성
    ↓
Notion API로 지정된 페이지에 추가
    ↓
Slack 스레드에 알림: "✅ SLAC-7 완료 + Notion에 정리되었습니다"
```

## Notion 페이지 구조 (안)

| 이슈 | 날짜 | 원인 | 해결 방법 | Jira 링크 |
|---|---|---|---|---|
| SLAC-7 로그인 500 에러 | 2026-04-22 | 세션 토큰 만료 미처리 | 토큰 갱신 로직 추가 | 링크 |

## 구현 계획

### 1. Notion API 연동
- Notion Integration 생성 + API Token 발급
- 대상 페이지/데이터베이스 ID 설정 (`application.yml`)
- `NotionApiClient` 인터페이스 + 구현체

### 2. 버그 요약 생성
- 완료 전환 시 Jira 이슈 설명 + 댓글을 Claude에 전달
- 원인(root cause)과 해결 방법(fix)을 구조화된 형태로 요약
- 프롬프트: "이 버그의 원인과 해결 방법을 각각 1-2문장으로 요약해줘"

### 3. 트리거
- `@지라 완료` 시 이슈 타입이 "버그"면 Notion 정리 실행
- 또는 Jira Webhook으로 상태 변경 감지 (운영 환경)

### 영향 범위
- `NotionApiClient` (새 파일) — Notion API 호출
- `SlackEventController` — 완료 처리 후 Notion 정리 분기
- `application.yml` — Notion 설정 추가
- `.env` — `NOTION_API_TOKEN`, `NOTION_DATABASE_ID`

### 선행 조건
- Notion Integration 생성 및 페이지 공유
- Notion API Token 발급
- 대상 데이터베이스 구조 확정
