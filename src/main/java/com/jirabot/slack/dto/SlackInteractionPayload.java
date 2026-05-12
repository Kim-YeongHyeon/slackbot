package com.jirabot.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// STUDY: Slack interaction payload는 snake_case JSON. @JsonProperty로 매핑.
// @JsonIgnoreProperties(ignoreUnknown = true) — Slack이 추가 필드를 보내도 역직렬화 실패 방지.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackInteractionPayload(
        String type,
        SlackUser user,
        SlackChannel channel,
        SlackMessage message,
        List<SlackAction> actions
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackUser(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackChannel(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackMessage(String ts) {}

    // STUDY: Slack action JSON uses snake_case "action_id" — @JsonProperty maps it to Java camelCase.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackAction(
            @JsonProperty("action_id") String actionId,
            String value
    ) {}
}
