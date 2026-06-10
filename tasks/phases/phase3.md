# Phase 3 — JiraSyncService + JPA + PostgreSQL (이슈 로컬 캐시)

> **목표:** Jira 데이터를 로컬 DB에 주기적으로 동기화하여 빠른 조회 기반 마련  
> **기간:** 5월 3주차 ~ 6월 1주차  
> **이전 Phase:** [phase2.md](../phases/phase2.md)  
> **다음 Phase:** [phase4.md](../phases/phase4.md)

---

## 체크리스트

> **구현 상태(2026-06-09):** Sync/캐시 핵심은 구현됨(`JiraSyncServiceImpl`, `IssueRepository`, `IssueEntity.syncedAt`).
> 단 실제와 계획이 다른 점: **동기화는 10~30분 주기가 아니라 매일 08:00 cron + 기동 시 1회 + 검색 시 on-demand**.
> **Flyway 미도입**(여전히 `ddl-auto=update` — lessons L8), **동기화 실패 시 Slack 알림 없음(로그만)**. 아래 `[ ]`는 stale.

### JiraSyncService
- [ ] `@Scheduled` Jira 이슈 전체 동기화 (주기: 10~30분 간격)  ← 실제: 매일 08:00 cron
- [ ] 신규 / 변경 / 삭제 이슈 Upsert 처리 (`saveOrUpdate` 또는 `merge`)
- [ ] 동기화 실패 시 Slack DM 또는 로그 알림  ← 실제: 로그만, DM 미구현
- [ ] 동기화 상태 추적 (`last_synced_at` 컬럼)

### JPA + PostgreSQL 이슈 로컬 캐시
- [ ] Jira 이슈 필드 → Entity 매핑 (summary, description, status, assignee, storyPoint 등)
- [ ] 인덱스 설계 (status, assignee, created_at — Phase 4 통계/조회 쿼리 기준으로 미리 설계)
- [ ] Flyway DB 마이그레이션 스크립트 관리 (스키마 변경 이력 추적)
- [ ] 대량 Upsert 배치 처리 (`saveAll` + chunk 단위)

---

## 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| `@Scheduled` + `@Transactional` 범위 과대 | 전체 동기화 시 긴 락 보유 | chunk 단위로 트랜잭션 분리 (`REQUIRES_NEW`) |
| Jira API Rate Limit (429) | 동기화 실패, 데이터 누락 | 지수 백오프 + Spring Retry 조합. 429 응답 시 즉시 재시도 금지 |
| 스키마 드리프트 | 로컬/운영 DB 불일치 → 앱 기동 실패 | Flyway 반드시 Phase 3 초반에 도입. 직접 DDL 금지 |
| 인덱스 누락 | Phase 4 통계 쿼리 풀스캔 | Phase 4 쿼리 패턴 미리 파악하고 인덱스 선제 설계 |

---

## 학습 병행

- `@Scheduled` cron 표현식 / `ThreadPoolTaskScheduler`
- JPA 배치 처리 (`saveAll`, `@Modifying`, bulk insert)
- Flyway 마이그레이션 (`V1__init.sql` 네이밍 규칙)
- PostgreSQL 인덱스 전략 (복합 인덱스, 부분 인덱스)
