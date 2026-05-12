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

        // First block: section with issue info
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("section");
        String sectionText = blocks.get(0).path("text").path("text").asText();
        assertThat(sectionText).contains("PROJ-1");
        assertThat(sectionText).contains("Login 500 error");

        // Second block: divider
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("divider");

        // Third block: actions with 2 buttons
        JsonNode actions = blocks.get(2);
        assertThat(actions.path("type").asText()).isEqualTo("actions");
        JsonNode elements = actions.path("elements");
        assertThat(elements.size()).isEqualTo(2);
        assertThat(elements.get(0).path("action_id").asText()).isEqualTo("jira_transition_in_progress");
        assertThat(elements.get(0).path("value").asText()).isEqualTo("PROJ-1");
        assertThat(elements.get(1).path("action_id").asText()).isEqualTo("jira_transition_done");
        assertThat(elements.get(1).path("value").asText()).isEqualTo("PROJ-1");
        assertThat(elements.get(1).path("style").asText()).isEqualTo("primary");
    }

    @Test
    void buildIssueCreatedBlocks_withSimilar_includesWarningSection() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.FEATURE, 5, "Dark mode", "summary");
        IssueEntity similar = new IssueEntity("PROJ-99", "Dark theme support", "작업",
                "진행 중", "진행 중", null, 3.0, "reporter", "desc",
                Instant.now(), Instant.now());

        String json = BlockKitBuilder.buildIssueCreatedBlocks(
                "PROJ-100", "https://jira.example.com/browse/PROJ-100",
                classification, List.of(similar));

        JsonNode blocks = mapper.readTree(json);
        // Section + warning section + divider + actions = 4 blocks
        assertThat(blocks.size()).isEqualTo(4);

        // Warning section
        String warningText = blocks.get(1).path("text").path("text").asText();
        assertThat(warningText).contains("유사한 이슈");
        assertThat(warningText).contains("PROJ-99");
        assertThat(warningText).contains("Dark theme support");
    }

    @Test
    void buildIssueCreatedBlocks_escapesSpecialChars() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 2, "Title with \"quotes\"", "summary");

        String json = BlockKitBuilder.buildIssueCreatedBlocks(
                "PROJ-1", "https://jira.example.com/browse/PROJ-1",
                classification, List.of());

        // Should be valid JSON despite special characters
        JsonNode blocks = mapper.readTree(json);
        assertThat(blocks.isArray()).isTrue();
    }

    @Test
    void buildIssueCreatedBlocks_doneButton_hasConfirmDialog() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 1, "Test issue", "summary");

        String json = BlockKitBuilder.buildIssueCreatedBlocks(
                "PROJ-1", "https://jira.example.com/browse/PROJ-1",
                classification, List.of());

        JsonNode blocks = mapper.readTree(json);
        JsonNode actionsBlock = blocks.get(2); // divider is index 1, actions is index 2
        JsonNode doneButton = actionsBlock.path("elements").get(1);
        JsonNode confirm = doneButton.path("confirm");

        assertThat(confirm.isMissingNode()).isFalse();
        assertThat(confirm.path("title").path("type").asText()).isEqualTo("plain_text");
        assertThat(confirm.path("title").path("text").asText()).isEqualTo("확인");
        assertThat(confirm.path("text").path("type").asText()).isEqualTo("mrkdwn");
        assertThat(confirm.path("text").path("text").asText()).isEqualTo("정말 완료 처리하시겠습니까?");
        assertThat(confirm.path("confirm").path("text").asText()).isEqualTo("완료");
        assertThat(confirm.path("deny").path("text").asText()).isEqualTo("취소");
    }

    @Test
    void buildCompletedBlocks_preservesOriginalBlocks() throws Exception {
        // Build original blocks simulating an issue created message
        ArrayNode originalBlocks = mapper.createArrayNode();
        var section = mapper.createObjectNode();
        section.put("type", "section");
        var text = mapper.createObjectNode();
        text.put("type", "mrkdwn");
        text.put("text", "Original issue info");
        section.set("text", text);
        originalBlocks.add(section);

        var divider = mapper.createObjectNode();
        divider.put("type", "divider");
        originalBlocks.add(divider);

        var actions = mapper.createObjectNode();
        actions.put("type", "actions");
        originalBlocks.add(actions);

        String json = BlockKitBuilder.buildCompletedBlocks(
                "PROJ-1", ":white_check_mark:", "완료", "testuser", originalBlocks);

        JsonNode result = mapper.readTree(json);
        assertThat(result.isArray()).isTrue();
        // Original section + divider (actions removed) + result section = 3
        assertThat(result.size()).isEqualTo(3);

        // First block preserved
        assertThat(result.get(0).path("type").asText()).isEqualTo("section");
        assertThat(result.get(0).path("text").path("text").asText()).isEqualTo("Original issue info");

        // Divider preserved
        assertThat(result.get(1).path("type").asText()).isEqualTo("divider");

        // Result section added at end
        assertThat(result.get(2).path("type").asText()).isEqualTo("section");
        String resultText = result.get(2).path("text").path("text").asText();
        assertThat(resultText).contains("PROJ-1");
        assertThat(resultText).contains("완료");
        assertThat(resultText).contains("testuser");
    }

    @Test
    void buildCompletedBlocks_withNullOriginalBlocks_returnsResultOnly() throws Exception {
        String json = BlockKitBuilder.buildCompletedBlocks(
                "PROJ-1", ":white_check_mark:", "완료", "testuser", null);

        JsonNode result = mapper.readTree(json);
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).path("type").asText()).isEqualTo("section");
    }
}
