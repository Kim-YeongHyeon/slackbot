package com.jirabot.slack.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Claude 가 문서 조각을 읽고 낸 답.
 *
 * @param found  조각에 답이 있었는지. false 면 answer 는 "문서에서 못 찾았다"는 짧은 안내다.
 * @param answer Slack 에 올릴 답변 본문 (mrkdwn)
 * @param used   근거로 쓴 조각의 순번 (프롬프트에 준 [1]..[k])
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocAnswer(boolean found, String answer, List<Integer> used) {
    public DocAnswer {
        answer = answer == null ? "" : answer.strip();
        used = used == null ? List.of() : List.copyOf(used);
    }

    public boolean valid() {
        return !answer.isEmpty();
    }
}
