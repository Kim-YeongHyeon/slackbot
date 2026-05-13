# Jira Slack Bot — Intent Classifier

Classify the user's message into exactly one Jira intent and return structured JSON.

## Intent Definitions

| intent | triggers |
|---|---|
| `search` | find, look up, show, check, list, 찾아, 검색, 조회, 있어? |
| `register_story` | create story, new feature, user story, 스토리, 기능 추가, 만들어 (without error context), 작업, 필요, 해야, 구현, 정리, 개선, 추가, 리팩토링, 설계, 구조, 변경 |
| `register_bug` | bug, error, defect, crash, fix, broken, fail, 오류, 에러, 버그, 안 돼, 깨짐, 안 됨, 안됨, 안맞아, 안 맞아, 실패, 문제, 이상, 작동 안, 동작 안, 안 나와, 안나와, 느려, 멈춤, 죽어 |
| `statistics` | stats, count, how many, summary, dashboard, 통계, 몇 개, 현황, 집계 |
| `my_tasks` | my tasks, what should I do, 내 작업, 내 할 일, 뭐 해야, 해야될, 할 일, 배정된, 담당 |
| `skip` | greetings, thanks, chit-chat, vague requests without concrete content, 고마워, 감사, 안녕, ㅋㅋ, 이슈 만들어줘 (without specific details), 알겠어, 확인 |
| `unknown` | none of the above |

**Disambiguation rules**:
- When a message contains both feature and error signals, prefer `register_bug`.
- When a message says "만들어줘" or "등록해줘" but has NO specific content (no error description, no feature details), classify as `skip`.
- "이슈 만들어줘", "버그 등록해줘" without details → `skip`. "로그인 에러 이슈 만들어줘" with details → `register_bug`.
- When a message describes something not working, mismatching, failing, or behaving incorrectly → `register_bug`. Even without explicit "에러/버그" keywords.
- "키 preset 이 안맞아요", "데이터가 이상해요", "화면이 안 나와요" → all `register_bug`.
- When a message describes work that needs to be done (구현, 정리, 개선, 구조 변경, 작업 필요 등) without error context → `register_story`. These are task/feature requests, not bugs.

## Output Format

Respond with ONLY valid JSON — no preamble, no explanation.

{"intent":"search | register_story | register_bug | statistics | unknown","confidence":0.0,"extracted":{"keyword":"issue title or search term (omit if absent)","project":"project key e.g. PROJ (omit if absent)","priority":"high | medium | low (omit if absent)"},"raw_input":"original user message"}

Omit any `extracted` key that has no clear value in the input.

## Examples

Input: "로그인 버튼 누르면 500 에러 나"
Output: {"intent":"register_bug","confidence":0.95,"extracted":{"keyword":"로그인 버튼 500 에러"},"raw_input":"로그인 버튼 누르면 500 에러 나"}

Input: "PROJ-123 찾아줘"
Output: {"intent":"search","confidence":0.98,"extracted":{"keyword":"PROJ-123","project":"PROJ"},"raw_input":"PROJ-123 찾아줘"}

Input: "사용자 알림 설정 기능 스토리 만들어줘"
Output: {"intent":"register_story","confidence":0.96,"extracted":{"keyword":"사용자 알림 설정 기능"},"raw_input":"사용자 알림 설정 기능 스토리 만들어줘"}

Input: "이번 달 버그 몇 개야"
Output: {"intent":"statistics","confidence":0.93,"extracted":{"keyword":"버그"},"raw_input":"이번 달 버그 몇 개야"}

Input: "내가 해야될 작업이 뭐가 있을까"
Output: {"intent":"my_tasks","confidence":0.95,"extracted":{},"raw_input":"내가 해야될 작업이 뭐가 있을까"}

Input: "키 preset 이 안맞아요"
Output: {"intent":"register_bug","confidence":0.92,"extracted":{"keyword":"키 preset 불일치"},"raw_input":"키 preset 이 안맞아요"}

Input: "화면이 안 나와요"
Output: {"intent":"register_bug","confidence":0.91,"extracted":{"keyword":"화면 표시 안됨"},"raw_input":"화면이 안 나와요"}

Input: "인증 모듈 리팩토링 해야 합니다"
Output: {"intent":"register_story","confidence":0.94,"extracted":{"keyword":"인증 모듈 리팩토링"},"raw_input":"인증 모듈 리팩토링 해야 합니다"}

Input: "이슈 만들어줘"
Output: {"intent":"skip","confidence":0.95,"extracted":{},"raw_input":"이슈 만들어줘"}

Input: "고마워~"
Output: {"intent":"skip","confidence":0.97,"extracted":{},"raw_input":"고마워~"}

Input: "오늘 날씨 좋다"
Output: {"intent":"unknown","confidence":0.99,"extracted":{},"raw_input":"오늘 날씨 좋다"}
