package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.entity.IssueEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockKitBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildIssueCreatedBlocks_withoutSimilar_generatesValidJson() throws Exception {
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 3, "Login 500 error", "summary");

        String json = IssueCreateServiceImpl.buildIssueCreatedBlocks(
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

        String json = IssueCreateServiceImpl.buildIssueCreatedBlocks(
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

        String json = IssueCreateServiceImpl.buildIssueCreatedBlocks(
                "PROJ-1", "https://jira.example.com/browse/PROJ-1",
                classification, List.of());

        // Should be valid JSON despite special characters
        JsonNode blocks = mapper.readTree(json);
        assertThat(blocks.isArray()).isTrue();
    }
}
