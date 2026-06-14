# Lessons

## L1 — `@Async` fire-and-forget 에 `CallerRunsPolicy` 금지

**Context**: Task #6 (AsyncConfig). Slack 3초 ack 제약이 있는 fire-and-forget 비동기 경로.

**Mistake**: pool+queue 포화 시 `CallerRunsPolicy` 를 선택. "조용한 누락보다 낫다"는 논리였으나 실제 영향은 "tomcat http-nio 스레드가 task 완료까지 블록 → `ResponseEntity.ok()` 지연 → Slack 3초 내 ack 실패 → 재전송 → 이슈 중복 생성".

**Rule**:
- Slack/webhook 처럼 외부가 timeout 후 재시도하는 계약에서 `@Async` 내부 executor 는 `AbortPolicy` 또는 커스텀 reject handler 사용.
- `AsyncConfigurer.getAsyncUncaughtExceptionHandler()` override 해서 `RejectedExecutionException` 을 warn 로그로 흡수.
- 포화는 "조용히 버리고 가시화" — caller 스레드 블록으로 ack 계약을 깨면 더 비싼 중복을 만든다.

**How to apply**: 외부 webhook 을 받아 비동기 처리하는 모든 ThreadPoolTaskExecutor 설정 시 rejection policy 선택을 먼저 고민. CPU 작업 내부 파이프라인일 때만 CallerRunsPolicy 후보.

---

## L2 — Verification Before Done: 환경 제약은 blocker 로 보고, completed 로 닫지 말 것

**Context**: Task #6 완료 보고. JDK 17 미설치로 `./gradlew test` 실행 불가능. 정적 리뷰만으로 completed 표시.

**Mistake**: "환경 복구 후 실패 시 대응" 이라는 소급 약속으로 completed 마킹. 10년차 관점에서는 반-패턴.

**Rule**:
- 테스트가 실행 불가능한 환경 이슈 = 작업의 Definition-of-Done 미충족.
- `in_progress` 로 유지 + blocker 를 team-lead 에게 명시적으로 보고.
- team-lead 가 환경 복구 후 테스트 통과 확인 → team-lead 가 completed 마킹.

**How to apply**: task 완료 시점 checklist — (1) 코드 리뷰 (2) 로컬 테스트 실행 성공 (3) 관련 문서/contract 반영. (2) 가 환경으로 막히면 task 는 열어두고 blocker 로 넘긴다.

---

## L3 — 외부 프로세스 호출은 얇은 interface 뒤에 둔다

**Context**: Anthropic HTTP API → `claude -p` CLI 서브프로세스 전환. `ClaudeApiClientImpl` 이 `ProcessBuilder` 를 직접 사용하면 단위 테스트가 OS 바이너리(`/bin/true`, 특정 버전의 `claude`)에 의존 → CI 환경별로 결과가 달라짐.

**Rule**: 외부 프로세스 호출은 반드시 얇은 interface (예: `ProcessRunner`) 뒤에 둔다. 구현체 하나(`DefaultProcessRunner`)는 실제 `ProcessBuilder` 를 쓰고, 테스트에서는 Mockito mock 을 주입한다.

**Why**: 직접 `ProcessBuilder` 호출은 테스트에서 OS-specific 바이너리 존재에 의존 → CI flaky. 또한 stdout/stderr 드레인, 타임아웃, 자식 프로세스 정리 같은 반복 로직을 한 곳에 모을 수 있다.

**How to apply**: 로컬 CLI 통합을 제안하기 전에 다음 3가지를 먼저 확인한다.
1. **배포 환경**: 바이너리가 실제로 실행 경로에 배치되는가? (컨테이너/CI 이미지 포함)
2. **인증 수명**: 세션/토큰의 만료/회전 주체가 누구인가? (사용자? 배포 스크립트?)
3. **스폰 latency**: 서브프로세스 스폰 + 언어 런타임 init 을 응답 SLA 가 수용할 수 있는가?
위 3가지가 모두 녹색이면 interface + 기본 구현 + Mockito 테스트 패턴으로 채택.

---

## L4 — UI 표시명(localized) ≠ 저장된 name. API 관점에서는 저장된 name 이 진실

**Context**: Jira Cloud Team-managed 프로젝트. 사용자가 UI 에서 이슈 타입을 "Task/Bug/Story" 로 본다고 말했으나, `/rest/api/3/project/{KEY}` 호출 결과 `issueTypes` 의 `.name` 이 한글 "작업/버그/스토리" 로 반환됨.

**Mistake**: 사용자 UI 보고를 그대로 받아들여 봇이 "Task"/"Bug"/"Story" 를 Jira 로 보내게 두면 400 `issuetype ... does not exist`. UI 는 사용자 언어 설정에 따라 자동 번역 레이어를 씌우지만, **저장소의 실제 `name` 은 사이트 생성 시점 언어**로 고정됨.

**Rule**:
- Jira/Atlassian API 로 이슈 타입·상태·우선순위·필드명을 보낼 때는 **반드시 REST API 응답의 raw `name` 을 기준**으로 매핑.
- 사용자 스크린샷/구두 보고에 "영어로 보인다" 는 답변이 나와도 UI 착시 가능성을 의심. `curl ... /project/{KEY}` 한 번이 진실.
- Team-managed (next-gen) 프로젝트는 `PUT /rest/api/3/issuetype/{id}` 리네임 불가 (Jira 가 400 "전역 이슈 유형이 아님" 으로 거절). 수정 불가 상황에서는 **봇 코드에서 매핑**이 최소 변경이자 회복 탄력성 높음.

**Why**: 표시명과 저장명의 분리는 i18n 이 있는 모든 SaaS 에서 공통 패턴 (Jira, Linear, Salesforce 등). API 호환성은 "로컬화된 UI" 가 아니라 "저장된 식별자/이름" 에만 성립함.

**How to apply**:
1. 외부 SaaS 와의 enum/name 매핑 작업이 생기면 **UI 스크린샷 대신 API 응답을 1순위 근거**로.
2. 사용자가 "영어로 보인다/한국어로 보인다" 라고 할 때 API raw 결과로 교차 확인 후 진행.
3. 저장된 name 이 바꾸기 어려운 제약이면 (예: Team-managed 리네임 불가, 레거시 시스템) 자기 코드 매핑이 가장 저렴한 해결책. UI 변경 협상은 비용이 크고 깨지기 쉬움.

---

## L5 — 핸드오프/문서의 외부 식별자는 재개 시점에 라이브로 교차검증

**Context**: 2026-06-01 세션 재개. HANDOFF.md 는 `프로젝트=SLAC`, `SP=customfield_10016`, `모델=sonnet-4-5`, "blocker 3개 미해소(POSTGRES_PASSWORD/Docker/ngrok)" 로 기술. 실제 라이브 상태는 전부 달랐다 — `.env` 의 `JIRA_PROJECT_KEY=ES2`, SP 기본값 `customfield_10036`, 세 blocker 모두 이미 해소(이 호스트는 맥북이 아닌 Linux), 코드도 Phase 1 을 한참 넘어 진화(테스트 10→23클래스). 게다가 8080/3000 에는 4일째 도는 **구버전(0.0.2) jar** 가 있었다.

**Mistake (반복 위험)**: 핸드오프 문서의 식별자/상태를 사실로 받아들이고 곧장 작업하면, 존재하지 않는 SLAC-2/3 를 삭제하거나 영문 이슈타입으로 400 을 맞는 등 헛발질을 한다.

**Rule**:
- 세션 재개 시 **모든 외부 식별자(프로젝트 키, 커스텀 필드 ID, 이슈타입 name, 모델 버전)와 "blocker" 목록을 라이브 소스로 먼저 검증**한 뒤 행동한다. 핸드오프 문서는 "사실" 이 아니라 "가설".
- 검증 순서: `.env` 실제값 → Jira `/project/{KEY}` 와 `/myself` → 실행 중인 프로세스/포트 → 그다음 코드.

**How to apply**: 재개 첫 단계에서 (1) `.env` 의 핵심 키 실제값 확인, (2) 라이브 API 한 번씩 ping, (3) `ps`/포트로 무엇이 이미 돌고 있는지 확인. 문서와 어긋나면 문서를 갱신하고 사용자에게 드리프트를 보고.

---

## L6 — createmeta 의 "생성 화면 필드 목록" ≠ create API 가 실제로 허용하는 필드

**Context**: ES2 의 `버그` 이슈타입에 SP 를 넣을 수 있는지 검증. `createmeta?expand=...fields` 응답에서 `버그` 의 필드에는 `customfield_10036` 이 **없었다**. 이를 근거로 "BUG 생성 시 SP 세팅이 400 날 것" 이라는 결함을 추정했다.

**Mistake**: 사용자가 "버그에도 SP 적을 수 있다, 다시 확인해라" 고 교정. 실제 `POST /rest/api/3/issue` 로 `버그`+`customfield_10036:2` 를 보내니 **201 생성 성공**. createmeta 는 "구성된 생성 화면" 에 노출된 필드만 나열할 뿐, API 가 create 시 허용하는 필드의 계약이 아니다.

**Rule**:
- createmeta 는 화면(screen) 힌트일 뿐 **create 가 무엇을 받아들이는지에 대한 진실이 아니다**. 메타데이터만으로 결함을 단정하지 말 것 (L4 의 연장: "실제 동작이 진실").
- 애매하면 **실제 create/transition 시도가 최종 판정**. 400 이면 무해(생성 안 됨), 201 이면 정리하면 된다 — 한 번의 실측이 추측 한 시간을 이긴다.

**How to apply**: 외부 API 의 허용 여부를 metadata 로 추론하게 되면, 결론 내리기 전에 무해한 실측(샘플 호출, 실패 시 부수효과 없음)을 1회 수행한다. 특히 "이건 안 될 것" 같은 부정 단정 전에.

---

## L7 — JQL 의 issuetype 매칭 이름 ≠ API 응답의 표시명 (L4 의 JQL 판)

**Context**: Notion 백필에서 ES2 버그를 `project = ES2 AND issuetype = "버그"` 로 조회 → **0건**. 그런데
`project = ES2` 로 받으면 응답의 `issuetype.name` 은 분명 "버그"였고 14/104건 존재했다.

**Mistake**: 응답에 보이는 표시명("버그")이 JQL 매칭에도 통할 거라 가정. 실측 결과:
- `issuetype = "버그"` → 0
- `issuetype = Bug` → 매칭됨 (영문 정식명)
- `issuetype = 10034` → 매칭됨 (id)

즉 한국어 사이트라도 **JQL 이 매칭하는 issuetype 이름은 영문 정식명(또는 id)** 이고, **응답/생성(name) 은 표시명("버그")**. 같은 필드가 문맥마다 다른 값을 요구한다.

**Rule**:
- JQL 에서 issuetype/상태 등을 **표시명(localized)으로 필터하지 말 것**. 영문 정식명 또는 id 를 쓰거나,
  더 안전하게는 **필터를 빼고 받아서 응답의 name 으로 클라이언트 필터**(코드가 쓰는 표시명과 일치).
- 생성(create)·표시는 표시명("버그"), JQL 검색은 정식명("Bug")/id — 둘을 구분해 다룬다.

**How to apply**: Jira 검색 기능을 만들 때 issuetype/status 를 JQL 조건에 표시명으로 넣었다면 1건이라도 실측.
0건이면 거의 이 함정 — `project=KEY` 전체 조회 후 응답 name 으로 거르는 방식으로 전환.

---

## L8 — `ddl-auto=update` 는 ADD COLUMN 에 DEFAULT 를 안 만든다 → NOT NULL 컬럼 추가가 조용히 실패

**Context**: PR #15 가 `UserMappingEntity` 에 `@Column(nullable=false) boolean reminderEnabled` 추가.
기존 row 가 있는 `user_mappings` 에 Hibernate 가 `ALTER TABLE ... ADD COLUMN reminder_enabled boolean NOT NULL`
(DEFAULT 절 없음) 실행 → Postgres 가 거부(`contains null values`). Hibernate 는 이를 **WARN 으로만** 남기고
앱을 정상 기동 → 컬럼 미생성. 이후 그 컬럼을 SELECT 하는 모든 쿼리가 런타임에 깨짐(이슈 생성 시 매핑 조회 실패).

**Mistake**: 엔티티 STUDY 주석이 "ddl-auto=update 가 컬럼 자동 추가하고 기존 row 는 false 백필된다"고 단정.
실제로 Hibernate 의 schema update 는 **ALTER ADD COLUMN 에 DEFAULT 를 생성하지 않으며, 마이그레이션 실패를
치명적 에러로 올리지 않고 WARN 후 계속 기동**한다. "앱이 떴으니 스키마도 맞겠지" 가정이 함정.

**Rule**:
- `ddl-auto=update` 환경에서 **데이터 있는 테이블에 NOT NULL 컬럼을 추가**할 땐 반드시 엔티티에
  `@Column(nullable=false, columnDefinition="<type> default <v>")` 로 DEFAULT 를 명시. (boolean → `boolean default false`)
- 새 컬럼 추가 배포 후엔 **시작 로그에서 `alter table` WARN/ExceptionHandlerLoggedImpl 을 확인**하거나
  `\d <table>` 로 컬럼 실재 여부를 검증. WARN 은 안 보면 묻힌다.
- 이미 깨진 운영 DB 복구는 `ALTER TABLE <t> ADD COLUMN IF NOT EXISTS <c> <type> NOT NULL DEFAULT <v>;`
  (앱 재시작 불필요 — 떠있는 앱 쿼리가 즉시 통과).
- 근본 해결: `application.yml` 주석대로 Flyway + `ddl-auto=validate`. **→ v0.0.24 에서 적용 완료**
  (V1 baseline + baseline-on-migrate, 이후 스키마 변경은 V<N> 마이그레이션으로만. 이 함정 자체가 구조적으로 제거됨).

**How to apply**: 엔티티에 NOT NULL 컬럼을 추가하는 PR 을 만들거나 리뷰할 때, 대상 테이블에 데이터가 있을 수 있으면
columnDefinition DEFAULT 가 있는지 먼저 확인. 없으면 이 함정. 그리고 "column does not exist" 런타임 에러는
스키마 마이그레이션 실패를 1순위로 의심.

---

## L9 — Slack Block Kit: 한 메시지 내 모든 버튼의 action_id 는 유일해야 한다

**Context**: "진행 중" 전환 시 repo 12개를 버튼으로 띄우는 기능(v0.0.17). 모든 버튼에 같은 action_id `jira_create_branch` 를 부여.

**Mistake**: 단위 테스트(JSON 구조 검증)는 통과했지만, 실제 Slack `chat.postMessage` 가 `invalid_blocks: "action_id ... already exists"` 로 거부 → 버튼 메시지가 아예 안 떴다. Slack 은 한 메시지 안의 모든 interactive element 의 action_id 가 유일하길 요구한다. 라우팅 편의로 동일 action_id 를 재사용한 게 원인.

**Rule**:
- 같은 메시지에 같은 종류 버튼을 여러 개 둘 때 action_id 를 `prefix_N`(또는 `prefix:key`)로 **고유화**하고, 핸들러는 `actionId.startsWith(prefix)` 로 라우팅한다. 식별 데이터는 버튼 `value` 에 싣는다.
- Block Kit 을 새로 만들면 단위 테스트(구조)만 믿지 말고 **실제 Slack API 응답(ok/invalid_blocks)을 로그로 확인**하거나 Block Kit Builder 로 검증한다. Slack 측 검증 규칙(action_id 유일성, 25개 한도 등)은 우리 JSON 직렬화 테스트가 못 잡는다.

**How to apply**: 반복 버튼 생성 루프를 보면 action_id 고유성부터 점검. 배포 후 첫 사용 시 `/tmp/slackbot.log` 에서 `block message sent ... "ok":false` 를 grep 해 invalid_blocks 를 조기 발견.

---

## L10 — 외부 이벤트 "수신" 기능의 DoD 는 공급자측 등록 + E2E 1발까지

**Context**: 할당 DM 알림(v0.0.20). 수신 엔드포인트·매핑·DM 발송 로직을 만들고 단위테스트까지 통과시켰지만,
실사용에서 "다른 팀원이 할당했는데 알림이 안 온다" 보고. 조사 결과 **Jira 에 웹훅이 아예 등록돼 있지 않았고**
(`GET /rest/webhooks/1.0/webhook` → `[]`), 터널이 :3000(Go 봇)만 노출해 등록할 수 있는 URL 자체도 없었다.
이전부터 있던 스레드 상태변경 알림·Notion 자동 동기화도 같은 이유로 라이브에서 동작한 적이 없던 것.

**Mistake**: webhook "소비자" 코드만 검증하고 "공급자(Jira)→봇" 경로의 존재를 가정했다. 우리 쪽 로그에
수신 기록이 없는 것도 INFO 레벨에서 수신/생략이 무음이라 눈치채지 못했다.

**Rule**:
- 외부 시스템이 우리를 호출하는 기능(webhook/콜백)의 완료 기준: (1) 수신 코드 (2) **공급자측 등록 실재 확인**
  (등록 API GET 1회) (3) **실제 또는 합성 페이로드로 E2E 1발** (터널/프록시 포함 전체 경로).
- 공급자측 등록은 별도 권한(Jira 사이트 관리자 등)이 필요할 수 있다 — 기능 설계 시점에 권한 보유자를 확인.
- 수신 자체와 분기 결정(발송/생략 + 사유)은 **INFO 로 로깅** — "도착 안 함"과 "도착했지만 조용히 skip"을
  운영 로그만으로 구분할 수 있어야 사용자 문의에 답할 수 있다.

**How to apply**: webhook 류 기능을 만들면 마지막에 `curl <공급자 등록목록 API>` 와 합성 페이로드 E2E 를
체크리스트로 수행. 터널 구조가 바뀌면(노출 포트 변경 등) 등록 URL 들도 함께 점검.

---

## L11 — 새 JPA 리포지토리를 추가하면 SecurityConfigIntegrationTest 에 @MockitoBean 도 추가

**Context**: v0.0.29(ResponseMetricRepository)와 v0.0.32(FeatureRequestRepository) — 같은 세션에서 두 번 반복.
`SecurityConfigIntegrationTest` 는 `spring.autoconfigure.exclude` 로 DataSource/JPA 자동설정을 끄고
모든 리포지토리를 `@MockitoBean` 으로 대체하는 풀-컨텍스트 테스트다. 새 리포지토리가 어떤 빈의
생성자 의존성에 들어가면 이 테스트가 `NoSuchBeanDefinitionException` 으로 깨진다.

**Rule**: `repository/` 에 인터페이스를 추가하고 그것을 @Service/@RestController 가 주입받게 했다면,
**같은 커밋에서 `SecurityConfigIntegrationTest` 에 `@MockitoBean private XxxRepository xxx;` 를 추가**한다.

**How to apply**: 새 엔티티+리포지토리 작업의 체크리스트 = ①Flyway V<N> ②엔티티 ③리포지토리
④주입처 ⑤**SecurityConfigIntegrationTest @MockitoBean** ⑥테스트. ⑤를 빼먹으면 전체 테스트에서 5건이 무더기로 깨진다.

---

## L12 — 분류 프롬프트는 IntentClassifierEvalTest 로 전후를 측정하고, `--bare` 는 이 호스트에서 인증을 깬다

**Context**: v0.0.33. `claude -p` 분류용 `prompts/*.md` 개선 요청. (1) 외부 문서/서브에이전트는 "`--bare` 가
구독 인증을 깨지 않는다"고 했으나 실측은 정반대였고, (2) 프롬프트를 감으로 고치면 회귀를 못 본다.

**Mistake 회피**: 라이브로 `echo ... | claude -p --bare --system-prompt-file ...` 를 돌려보니
`{"is_error":true,"result":"Not logged in · Please run /login"}` (22ms 즉시 실패). `--bare` 는
keychain 읽기까지 스킵해서 구독(OAuth) 인증 토큰을 못 읽는다. 코드 주석(IntentClassifierImpl)이 맞았다.

**Rule**:
- `claude -p` 호출 플래그/동작은 **이 호스트에서 직접 실측**으로 확정한다 (L5/L6 연장). 특히 `--bare`(인증 깨짐),
  `--json-schema`(구조화 출력을 **툴 호출**로 처리 → `--max-turns` 1 더 소비, `max-turns 1/2` 와 충돌해 `error_max_turns`).
  파서가 이미 fence/잡텍스트를 strip 하므로 출력 견고성은 프롬프트가 아니라 코드가 보장.
- 분류 프롬프트(`prompts/haiku-classifier.md` 등)를 고치면 **반드시 전후로**
  `./gradlew test -Dintent.eval=true --tests "*IntentClassifierEvalTest"` 를 돌려 정확도 diff 를 확인한다
  (90케이스, 실제 CLI 호출, ~12분, 리포트 `build/reports/intent-eval/report.txt`). 임계 0.95.
- 흩어진 disambiguation 은 **우선순위 결정 절차(first-match-wins)**로 재구성하면 작은 모델(Haiku)에서 잘 듣는다.
  단, 절차 순서가 곧 동작이라 **순서 재배치가 회귀를 만들 수 있다**(예: "뭐 해야 해?" 단축 규칙이 "이번 스프린트에"
  문맥을 눌러 my_tasks 오분류). 우선순위가 높은 컨텍스트(스프린트/팀 키워드)를 단축 규칙보다 위에 둔다.
- `prompts/*.md` 는 런타임에 디스크에서 읽히므로(작업 디렉터리=repo 루트) **재배포 없이 즉시 반영**된다.
  단 인라인 폴백 상수(`ClaudeApiClientImpl.SYSTEM_PROMPT`)와 SP 기준은 함께 정합시키고(13 금지, 8 상한),
  jar 도 버전 맞춰 재배포해 드리프트를 막는다.

**How to apply**: 프롬프트 수정 PR = ①베이스라인 eval ②수정 ③단위테스트 ④eval 재측정(개선/무회귀 확인)
⑤커밋. 실패 케이스의 confusion matrix 를 보고 경계 규칙/예시를 보강한다.

---

## L13 — Jira Cloud 날짜는 `+0900`(콜론 없는 오프셋) → `Instant.parse` 가 조용히 실패한다

**Context**: v0.0.35. 대시보드 추이가 과거를 못 보여줌. 원인 추적 중 로컬 DB 의 `jira_created` 가 102개 중 97개 null,
`completed_at` 은 전부 null 임을 발견. 동기화는 매번 도는데 왜?

**Mistake (잠재)**: `JiraSyncServiceImpl.parseInstant` 가 `Instant.parse(s)` 만 썼다. `Instant.parse` 는
**`Z`(UTC) 형식만** 받는다. Jira Cloud REST 는 `2026-06-09T16:07:15.273+0900`(콜론 없는 오프셋)을 주므로
`DateTimeParseException` → catch 에서 **null 반환**. 즉 동기화로 들어온 모든 이슈의 생성일/완료일이 null 로
저장돼 추이·해결추이·평균해결시간이 전부 빈 채로 "정상 동작"하는 것처럼 보였다. (Slack 봇 생성 이슈만
`Instant.now()` 라 5건은 살아 있었음 — 그래서 더 안 보였다.)

**Rule**:
- 외부 시스템의 ISO 날짜를 파싱할 때 `Instant.parse` 단독 사용 금지. **`OffsetDateTime.parse`(콜론 오프셋/Z)
  + 콜론 없는 오프셋용 포맷터(`yyyy-MM-dd'T'HH:mm:ss.SSSZ`)** 를 순차 시도하고 마지막에 `Instant.parse` 폴백.
- 파싱 실패를 null 로 삼키는 코드는 **데이터 손실을 무음화**한다. null 이 다량이면 파서를 1순위로 의심.
- 날짜 의존 통계(추이/리드타임)를 만들면 **DB 에 실제로 날짜가 채워지는지** `count(col)` 로 한 번 실측한다.
- 완료 시각은 이 사이트에서 `resolutiondate` 가 비는 경우가 많다 → `statusCategoryChangedDate`(Done 진입 시각)
  폴백을 쓴다. (해결 버그/백필 공통)

**How to apply**: Jira(또는 임의 SaaS) 날짜를 Instant 로 바꾸는 지점을 보면 오프셋 형식(`+0900` vs `+09:00` vs `Z`)을
실측하고 견고 파서를 쓴다. 동기화가 채우는 컬럼은 배포 후 `SELECT count(col)` 로 null 비율을 확인.
