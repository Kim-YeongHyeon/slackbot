# Deferred — Slack 버튼 UI (Interactive Components)

> **등록일:** 2026-05-12
> **우선순위:** 중간
> **요청자:** 공식 봇 비교 분석
> **상태(2026-06-09):** ✅ **구현 완료** — `SlackInteractionController` (`/slack/interaction`),
> `BlockKitBuilder` 버튼(해야 할 일/진행 중/검토 중/완료/바로 완료) + `transitionIssue` 연동. 아래는 최초 계획 기록.

## 요구사항

이슈 생성 알림에 "진행 중", "완료" 버튼을 추가하여 클릭으로 상태 전환.

## 예시

```
✅ Jira 이슈가 등록되었습니다!
[SLAC-11] 로그인 에러 수정
분류: BUG | SP: 2

[진행 중 🔨] [완료 ✅]    ← 클릭 가능한 버튼
```

## 구현 계획

- Slack Block Kit으로 버튼 포함 메시지 전송
- Slack App에서 Interactivity & Shortcuts → Request URL 등록
- 버튼 클릭 시 `action_id` + `value`(이슈 키) 수신
- Spring에 `/api/slack/interaction` 엔드포인트 추가
- `JiraApiClient.transitionIssue()` 호출 (이미 구현됨)
- 버튼 클릭 후 메시지 업데이트 (상태 반영)

## 선행 조건

- Slack App에서 Interactivity 활성화
- 별도 Request URL 필요 (현재 Event Subscriptions URL과 다름)
