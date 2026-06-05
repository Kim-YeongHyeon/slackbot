package com.jirabot.slack.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.config.JiraProperties;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

class JiraApiClientImplTest {

    private MockWebServer server;
    private JiraApiClientImpl client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        var props = new JiraProperties(server.url("/").toString(), "u@x.com", "t", "PROJ", null, null);
        WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
        client = new JiraApiClientImpl(webClient, props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void createIssue_success_returnsKey() {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"id\":\"10001\",\"key\":\"PROJ-1\",\"self\":\"https://j/r/PROJ-1\"}"));

        JiraCreateResponse resp = client.createIssue(
                new IssueClassification(IssueClassification.IssueType.BUG, 2, "t", "s"), "U1", null);

        assertThat(resp.key()).isEqualTo("PROJ-1");
    }

    @Test
    void createIssue_epic_usesEpicTypeAndOmitsStoryPoint() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"id\":\"10009\",\"key\":\"PROJ-9\",\"self\":\"https://j/r/PROJ-9\"}"));

        // props 기본값이므로 epic 타입명은 "Epic", SP 필드는 customfield_10036
        client.createIssue(
                new IssueClassification(IssueClassification.IssueType.EPIC, 0, "GCP 배포 확장", "summary"),
                "U1", null);

        String body = server.takeRequest().getBody().readUtf8();
        // 에픽 타입으로 생성 + SP 커스텀 필드/라벨 미포함 + claude-epic 라벨 포함
        assertThat(body).contains("\"name\":\"Epic\"");
        assertThat(body).doesNotContain("customfield_10036");
        assertThat(body).contains("claude-epic");
        assertThat(body).doesNotContain("sp-");
    }

    @Test
    void createIssue_400_throwsNonTransient() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));

        assertThatThrownBy(() -> client.createIssue(
                new IssueClassification(IssueClassification.IssueType.FEATURE, 3, "t", "s"), "U", null))
                .isInstanceOf(JiraApiException.class);
    }

    @Test
    void createIssue_500_mappedToTransient() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> client.createIssue(
                new IssueClassification(IssueClassification.IssueType.OTHER, 1, "t", "s"), "U", null))
                .isInstanceOf(JiraTransientException.class);
    }
}
