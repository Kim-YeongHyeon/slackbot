package com.jirabot.slack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackEventEnvelope(
        String type,
        String challenge,
        SlackEventInner event,
        // STUDY: event_callback 에는 이 이벤트를 수신한 앱(=봇)의 user_id 가 담긴 authorizations 가 온다.
        //        메시지에 멘션이 여러 개일 때 "어느 멘션이 봇인지" 판별하는 유일한 근거 (v0.0.64).
        java.util.List<Authorization> authorizations
) {
    public static final String URL_VERIFICATION = "url_verification";
    public static final String EVENT_CALLBACK = "event_callback";

    // STUDY: 기존 3-인자 호출부(테스트)를 깨지 않는 위임 생성자. Jackson 은 canonical 생성자로 바인딩.
    public SlackEventEnvelope(String type, String challenge, SlackEventInner event) {
        this(type, challenge, event, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Authorization(String user_id) {}

    /** 봇 자신의 Slack user ID. authorizations 가 없으면 null. */
    public String botUserId() {
        return authorizations == null || authorizations.isEmpty()
                ? null : authorizations.get(0).user_id();
    }
}
