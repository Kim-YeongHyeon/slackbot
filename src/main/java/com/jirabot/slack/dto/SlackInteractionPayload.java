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

    // STUDY: blocks 필드를 JsonNode로 받으면 Block Kit 배열 구조를 그대로 보존할 수 있다.
    //        구체적인 DTO로 역직렬화하지 않아도 원본 블록을 재사용할 때 편리하다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackMessage(String ts, @JsonProperty("blocks") com.fasterxml.jackson.databind.JsonNode blocks) {}

    // STUDY: Slack action JSON uses snake_case "action_id" — @JsonProperty maps it to Java camelCase.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackAction(
            @JsonProperty("action_id") String actionId,
            String value
    ) {}
}
