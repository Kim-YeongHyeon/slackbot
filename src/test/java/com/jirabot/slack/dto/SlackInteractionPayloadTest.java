package com.jirabot.slack.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SlackInteractionPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesBlockActionsPayload() throws Exception {
        String json = """
                {
                  "type": "block_actions",
                  "user": {"id": "U123", "name": "testuser"},
                  "channel": {"id": "C456"},
                  "message": {"ts": "1234567890.123456"},
                  "actions": [
                    {"action_id": "jira_transition_done", "value": "PROJ-42"}
                  ]
                }
                """;

        SlackInteractionPayload payload = mapper.readValue(json, SlackInteractionPayload.class);

        assertThat(payload.type()).isEqualTo("block_actions");
        assertThat(payload.user().id()).isEqualTo("U123");
        assertThat(payload.user().name()).isEqualTo("testuser");
        assertThat(payload.channel().id()).isEqualTo("C456");
        assertThat(payload.message().ts()).isEqualTo("1234567890.123456");
        assertThat(payload.actions()).hasSize(1);
        assertThat(payload.actions().get(0).actionId()).isEqualTo("jira_transition_done");
        assertThat(payload.actions().get(0).value()).isEqualTo("PROJ-42");
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        String json = """
                {
                  "type": "block_actions",
                  "trigger_id": "xxx",
                  "enterprise": null,
                  "user": {"id": "U1", "name": "n", "team_id": "T1"},
                  "channel": {"id": "C1", "name": "general"},
                  "message": {"ts": "1.0", "text": "hello", "blocks": []},
                  "actions": [
                    {"action_id": "a1", "value": "v1", "block_id": "b1", "type": "button"}
                  ]
                }
                """;

        SlackInteractionPayload payload = mapper.readValue(json, SlackInteractionPayload.class);

        assertThat(payload.type()).isEqualTo("block_actions");
        assertThat(payload.actions().get(0).actionId()).isEqualTo("a1");
    }

    @Test
    void parsesEmptyActions() throws Exception {
        String json = """
                {
                  "type": "block_actions",
                  "user": {"id": "U1", "name": "n"},
                  "channel": {"id": "C1"},
                  "message": {"ts": "1.0"},
                  "actions": []
                }
                """;

        SlackInteractionPayload payload = mapper.readValue(json, SlackInteractionPayload.class);

        assertThat(payload.actions()).isEmpty();
    }

    @Test
    void parsesNullOptionalFields() throws Exception {
        String json = """
                {
                  "type": "view_submission",
                  "user": {"id": "U1", "name": "n"}
                }
                """;

        SlackInteractionPayload payload = mapper.readValue(json, SlackInteractionPayload.class);

        assertThat(payload.type()).isEqualTo("view_submission");
        assertThat(payload.channel()).isNull();
        assertThat(payload.message()).isNull();
        assertThat(payload.actions()).isNull();
    }
}
