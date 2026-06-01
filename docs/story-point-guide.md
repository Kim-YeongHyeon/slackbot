# Story Point 산정 규칙

> SP 관련 작업(분류 프롬프트 수정, 추정 로직, 리뷰 등)을 할 때 **항상 이 표를 기준**으로 한다.
> 피보나치 척도, 단위는 "1인 기준 소요 시간 + 불확실성".

| SP | 기간 | 크기 | 설명 |
|----|------|------|------|
| **1** | 반나절 이하 | small | 가장 가벼운 작업 |
| **2** | 하루 | medium | 일반적인 작업 A |
| **3** | 1~2일 | Large | 일반적인 작업 B |
| **5** | 2~3일 | X-large | 조금 무거운 작업 |
| **8** | 3~4일 | ⚠️ Warning | **스프린트에 넣을 수 있는 최대 크기** |

## 운영 원칙

- **8이 스프린트 단위 작업의 상한**이다. 8을 받은 작업은 가능하면 분할을 검토한다.
- **8을 초과한다고 판단되면(=13 이상)** 스프린트에 그대로 넣지 말고 **하위 작업으로 분할**한다.
  즉 분류기는 1·2·3·5·8 중 하나를 고르되, "8로도 안 담긴다"면 분할 필요를 알린다.
- 추정은 **노력(effort) + 불확실성(uncertainty)** 을 함께 본다. 재현/원인 조사가 여러 단계면 한 단계 올린다.

## 코드 연동 지점

- 분류 프롬프트(SP 추천)의 기준 문구: `ClaudeApiClientImpl.SYSTEM_PROMPT`
  ([src/main/java/com/jirabot/slack/client/ClaudeApiClientImpl.java](../src/main/java/com/jirabot/slack/client/ClaudeApiClientImpl.java))
- 라벨 `sp-N` 부여: `JiraApiClientImpl.buildRequest`
- 이 표를 바꾸면 위 두 곳도 함께 검토한다.
