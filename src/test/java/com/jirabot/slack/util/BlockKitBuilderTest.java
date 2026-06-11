package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockKitBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildBranchRepoButtons_hasButtonPerRepoWithEncodedValue() throws Exception {
        String json = BlockKitBuilder.buildBranchRepoButtons(
                "ES2-1", "feature/ES2-1-foo", List.of("envector-msa", "evi"));
        JsonNode blocks = MAPPER.readTree(json);

        // 권장 브랜치명이 안내 section 에 포함.
        assertThat(json).contains("feature/ES2-1-foo");

        // 마지막 블록이 actions 이고 repo 수만큼 버튼.
        JsonNode actions = blocks.get(blocks.size() - 1);
        assertThat(actions.path("type").asText()).isEqualTo("actions");
        JsonNode elements = actions.path("elements");
        assertThat(elements).hasSize(2);
        // Slack 은 메시지 내 action_id 유일성을 요구 → repo 마다 고유(prefix + 인덱스), 라우팅은 prefix 로.
        assertThat(elements.get(0).path("action_id").asText())
                .startsWith(BlockKitBuilder.ACTION_CREATE_BRANCH).isEqualTo(BlockKitBuilder.ACTION_CREATE_BRANCH + "_0");
        assertThat(elements.get(1).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_CREATE_BRANCH + "_1");
        assertThat(elements.get(0).path("action_id").asText())
                .isNotEqualTo(elements.get(1).path("action_id").asText());
        assertThat(elements.get(0).path("value").asText()).isEqualTo("ES2-1|envector-msa|feature/ES2-1-foo");
        assertThat(elements.get(1).path("value").asText()).isEqualTo("ES2-1|evi|feature/ES2-1-foo");
    }
}
