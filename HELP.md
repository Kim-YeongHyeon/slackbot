# 지라 Help

Slack에서 `@지라 help`를 입력하면 같은 내용이 채널에 표시됩니다. 운영자/개발자 참조용으로
이 문서를 함께 유지합니다. 명령 정의 자체는 `SlackEventController#HELP_TEXT`(단일 출처)를
따르므로, 명령을 추가/수정할 때는 코드와 이 문서를 함께 갱신해 주세요.

## 키워드 명령 (즉시 실행)

| 명령 | 동작 |
|---|---|
| `@지라 help` (`도움말`) | 이 도움말을 스레드 답글로 표시 |
| `@지라 scrum` (`스크럼`) | 스프린트 일일 리포트 채널 게시 |
| `@지라 내작업` (`my`) | 호출자의 진행 중인 작업 조회 |
| `@지라 작업 <이름>` | 지정한 팀원의 진행 중인 작업 조회 |
| `@지라 등록 <Jira 사용자명>` | 호출자의 Slack ↔ Jira 계정 매핑 등록 |
| `@지라 sync` (`동기화`) | Jira → 로컬 DB 수동 동기화 |
| `@지라 완료` (`done`) | 이슈 스레드 안에서 Jira 상태를 "완료"로 전환 |

## 스레드 액션 (이슈 생성 스레드에서 댓글로 사용)

| 명령 | 동작 |
|---|---|
| `@지라 하위작업 <내용>` | Sonnet 분류 → Jira 하위 작업 생성 |
| `@지라 댓글 <내용>` | Jira 이슈에 코멘트 추가 |
| `@지라 수정 <내용>` | Jira 설명 본문에 내용 추가(append) |
| 자연어 입력 | Haiku 스레드 액션 분류기가 의도 추정 후 위 액션 중 하나로 분기 |

## 자연어 입력 (AI 분류 → Jira 이슈 등록)

```
@지라 로그인 페이지에서 500 에러 발생          → :bug: 버그로 등록
@지라 다크모드 지원해주세요                    → :pencil: 기능 요청으로 등록
```

이슈 등록 시 AI가 자동으로 `BUG / FEATURE / OTHER` 분류와 Story Point를 추정하며, 동일/유사
이슈가 DB에 존재하면 경고 메시지를 함께 표시합니다.

## 외부 레퍼런스

빌드/런타임 관련 외부 문서는 변경이 잦지 않으므로 필요 시 아래를 참고하십시오.

- Spring Boot 3.5: <https://docs.spring.io/spring-boot/3.5.0>
- Gradle: <https://docs.gradle.org>
- Slack Events API: <https://api.slack.com/apis/events-api>
- Jira REST v3: <https://developer.atlassian.com/cloud/jira/platform/rest/v3/>
