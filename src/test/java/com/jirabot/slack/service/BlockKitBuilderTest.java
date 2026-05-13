package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.util.BlockKitBuilder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockKitBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildIssueCreatedBlocks_withoutSimilar_generatesValidJson() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 3, "Login 500 error", "summary");

        String json = BlockKitBuilder.buildIssueCreatedBlocks(
                "PROJ-1", "https://jira.example.com/browse/PROJ-1",
                classification, List.of());

        JsonNode blocks = mapper.readTree(json);
        assertThat(blocks.isArray()).isTrue();
        // Section + divider + actions = 3 blocks
        assertThat(blocks.size()).isEqualTo(3);

        // Actions: 해야 할 일, 진행 중, 바로 완료 = 3 buttons
        JsonNode actions = blocks.get(2);
        assertThat(actions.path("type").asText()).isEqualTo("actions");
        JsonNode elements = actions.path("elements");
        assertThat(elements.size()).isEqualTo(3);
        assertThat(elements.get(0).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_TODO);
        assertThat(elements.get(1).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_IN_PROGRESS);
        assertThat(elements.get(2).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_QUICK_DONE);
    }

    @Test
    void buildIssueCreatedBlocks_withSimilar_includesWarningSection() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.FEATURE, 5, "Dark mode", "summary");
        IssueEntity similar = new IssueEntity("PROJ-99", "Dark theme support", "Task",
                "진행 중", "진행 중", null, 3.0, "reporter", "desc",
                Instant.now(), Instant.now());

        String json = BlockKitBuilder.buildIssueCreatedBlocks(
                "PROJ-100", "https://jira.example.com/browse/PROJ-100",
                classification, List.of(similar));

        JsonNode blocks = mapper.readTree(json);
        assertThat(blocks.size()).isEqualTo(4);

        String warningText = blocks.get(1).path("text").path("text").asText();
        assertThat(warningText).contains("PROJ-99");
    }

    @Test
    void buildIssueCreatedBlocks_quickDoneButton_hasConfirmDialog() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 1, "Test issue", "summary");

        String json = BlockKitBuilder.buildIssueCreatedBlocks(
                "PROJ-1", "https://jira.example.com/browse/PROJ-1",
                classification, List.of());

        JsonNode blocks = mapper.readTree(json);
        JsonNode quickDoneButton = blocks.get(2).path("elements").get(2);
        assertThat(quickDoneButton.path("style").asText()).isEqualTo("primary");

        JsonNode confirm = quickDoneButton.path("confirm");
        assertThat(confirm.isMissingNode()).isFalse();
        assertThat(confirm.path("title").path("text").asText()).isEqualTo("확인");
    }

    @Test
    void buildTransitionedBlocks_includesNextButton() throws Exception {
        String json = BlockKitBuilder.buildTransitionedBlocks(
                "PROJ-1", ":clipboard:", "해야 할 일", "testuser",
                BlockKitBuilder.ACTION_IN_PROGRESS, "\ud83d\udd28 진행 중", null);

        JsonNode result = mapper.readTree(json);
        // result section + actions = 2
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).path("type").asText()).isEqualTo("section");
        assertThat(result.get(1).path("type").asText()).isEqualTo("actions");
        assertThat(result.get(1).path("elements").get(0).path("action_id").asText())
                .isEqualTo(BlockKitBuilder.ACTION_IN_PROGRESS);
    }

    @Test
    void buildTransitionedBlocks_doneButton_hasConfirm() throws Exception {
        String json = BlockKitBuilder.buildTransitionedBlocks(
                "PROJ-1", ":mag:", "검토 중", "testuser",
                BlockKitBuilder.ACTION_DONE, "\u2705 완료", null);

        JsonNode result = mapper.readTree(json);
        JsonNode doneButton = result.get(1).path("elements").get(0);
        assertThat(doneButton.path("confirm").isMissingNode()).isFalse();
        assertThat(doneButton.path("style").asText()).isEqualTo("primary");
    }

    @Test
    void buildCompletedBlocks_preservesOriginalAndNoButtons() throws Exception {
        ArrayNode originalBlocks = mapper.createArrayNode();
        var section = mapper.createObjectNode();
        section.put("type", "section");
        section.set("text", mapper.createObjectNode().put("type", "mrkdwn").put("text", "Info"));
        originalBlocks.add(section);
        originalBlocks.add(mapper.createObjectNode().put("type", "divider"));
        originalBlocks.add(mapper.createObjectNode().put("type", "actions"));

        String json = BlockKitBuilder.buildCompletedBlocks(
                "PROJ-1", ":white_check_mark:", "완료", "testuser", originalBlocks);

        JsonNode result = mapper.readTree(json);
        // section + divider (actions removed) + result section = 3
        assertThat(result.size()).isEqualTo(3);
        for (JsonNode block : result) {
            assertThat(block.path("type").asText()).isNotEqualTo("actions");
        }
    }

    @Test
    void buildCompletedBlocks_withNullOriginalBlocks() throws Exception {
        String json = BlockKitBuilder.buildCompletedBlocks(
                "PROJ-1", ":zap:", "바로 완료", "testuser", null);

        JsonNode result = mapper.readTree(json);
        assertThat(result.size()).isEqualTo(1);
        String text = result.get(0).path("text").path("text").asText();
        assertThat(text).contains("PROJ-1");
        assertThat(text).contains("바로 완료");
    }
}
