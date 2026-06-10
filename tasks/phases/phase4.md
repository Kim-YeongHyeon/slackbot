# Phase 4 — 슬랙봇 통계 기능

> **목표:** 팀 워크플로우 가시성 향상을 위한 자동 알림 / 통계 기능  
> **기간:** 6월 2주차 ~ 6월 3주차  
> **이전 Phase:** [phase3.md](../phases/phase3.md)  
> **다음 Phase:** [phase5.md](../phases/phase5.md)

---

## 체크리스트

> **구현 상태(2026-06-09):**
> - **일일 브리핑(전날 등록 이슈 채널 게시): 미구현.** 별개 기능인 `ReminderService`(개인 미해결 이슈 DM, 평일 09:00 스프린트 / 격주 전체)가 있으나 "브리핑"과는 다름.
> - **방치 이슈 알림: 부분 구현(v0.0.10).** 별도 채널 멘션이 아니라 **일일 리마인더 DM 안에서 "진행 중" N일(기본 7) 정체 이슈를 ⚠️ 태그**(`reminder.stale-days`). 진입 시각은 `IssueEntity.inProgressSince`.
> - **통계: `@지라 통계`(활성 스프린트) 구현.** 단 `/overdue`·**주간 리포트(금 18:00)**는 **미구현**.

### 일일 브리핑 — 어제 추가된 이슈 소개  (미구현)
- [ ] 매일 오전 9시 Slack 채널에 전날 등록된 이슈 목록 게시 (`@Scheduled`)
- [ ] 이슈 타입별(버그/Feature) 집계 포함
- [ ] Claude 요약: "어제 총 N개 이슈 — 버그 X, Feature Y. 주요: ..."

### 방치 이슈 알림 — In-Progress 오래된 이슈  (부분 구현: 리마인더 내 ⚠️ 태그)
- [x] In-Progress 상태가 N일(기본 7일) 이상인 이슈 감지 (`inProgressSince` 기준)
- [~] 담당자에게 알림 — 채널 멘션이 아니라 일일 리마인더 DM 내 태그로 제공
- [x] 임계일 설정 외부화 (`reminder.stale-days`)

### 추가 통계 기능
- [x] 이번 스프린트 현황 — `@지라 통계` (담당자별 / 상태별 / SP)
- [ ] `/overdue` 명령어: 마감일 초과 이슈 목록  (미구현)
- [ ] 주간 리포트 (매주 금요일 오후 6시 자동 게시)  (미구현)

---

## 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| 알림 피로 (alert fatigue) | 알림 무시 → 봇 사용 중단 | 채널별 알림 빈도 조절 옵션 처음부터 설계 |
| 통계 쿼리 풀스캔 | 응답 느림, DB 부하 | Phase 3에서 설계한 인덱스 활용 여부 EXPLAIN으로 검증 |
| Slack Block Kit 복잡도 | 메시지 빌더 코드 난잡 | 재사용 가능한 SlackMessageBuilder 유틸 클래스 분리 |

---

## 학습 병행

- Slack Block Kit (Section, Header, Divider 블록 구성)
- `@Scheduled` cron 표현식 고급 패턴
- PostgreSQL EXPLAIN / EXPLAIN ANALYZE (쿼리 성능 검증)
