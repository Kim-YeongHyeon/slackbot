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

    // STUDY: /rest/api/3/search/jql 응답 한 페이지(nextPageToken 없음)를 만든다.
    private static String searchResponse(String... summaries) {
        StringBuilder sb = new StringBuilder("{\"issues\":[");
        for (int i = 0; i < summaries.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"key\":\"PROJ-").append(100 + i)
              .append("\",\"fields\":{\"summary\":\"").append(summaries[i])
              .append("\",\"issuetype\":{\"name\":\"Story\",\"subtask\":false}}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    void findIssueKeyByName_exactMatchWins() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(searchResponse("결제 모듈 개선", "결제 모듈", "결제")));

        // 인덱스 1이 정확 일치 → PROJ-101
        assertThat(client.findIssueKeyByName("결제 모듈")).contains("PROJ-101");
    }

    @Test
    void findIssueKeyByName_partialMatchWhenNoExact() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(searchResponse("로그인 리팩토링", "결제 모듈 리스트")));

        // 정확 일치 없음 → 부분 일치(요약이 needle 포함) 첫 후보 PROJ-101
        assertThat(client.findIssueKeyByName("결제 모듈")).contains("PROJ-101");
    }

    @Test
    void findIssueKeyByName_noMatch_returnsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(searchResponse("전혀 다른 이슈", "또 다른 것")));

        assertThat(client.findIssueKeyByName("결제 모듈")).isEmpty();
    }

    @Test
    void findIssueKeyByName_excludesEpicViaJql() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(searchResponse("결제 모듈")));

        client.findIssueKeyByName("결제 모듈");

        String path = server.takeRequest().getPath();
        // JQL 에 issuetype != Epic 이 포함되어야 함(에픽은 하위작업 부모가 될 수 없음)
        assertThat(java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8))
                .contains("issuetype != Epic");
    }

    // ==================== 이슈 링크 ====================

    @Test
    void getIssueLinkTypes_parsesNameInwardOutward() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"issueLinkTypes\":["
                        + "{\"id\":\"10000\",\"name\":\"Blocks\",\"inward\":\"is blocked by\",\"outward\":\"blocks\"},"
                        + "{\"id\":\"10003\",\"name\":\"Relates\",\"inward\":\"relates to\",\"outward\":\"relates to\"}]}"));

        var types = client.getIssueLinkTypes();
        assertThat(types).hasSize(2);
        assertThat(types.get(0).name()).isEqualTo("Blocks");
        assertThat(types.get(0).outward()).isEqualTo("blocks");
        assertThat(types.get(0).inward()).isEqualTo("is blocked by");
    }

    @Test
    void linkIssues_putsKeysInCorrectDirectionSlots() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201));

        boolean ok = client.linkIssues("ES2-1352", "ES2-1532", "Blocks");

        assertThat(ok).isTrue();
        String body = server.takeRequest().getBody().readUtf8();
        // 방향 버그 트립와이어: inwardIssue=1352(막힌 쪽), outwardIssue=1532(막는 쪽)
        assertThat(body).contains("\"inwardIssue\":{\"key\":\"ES2-1352\"}");
        assertThat(body).contains("\"outwardIssue\":{\"key\":\"ES2-1532\"}");
        assertThat(body).contains("\"type\":{\"name\":\"Blocks\"}");
    }

    @Test
    void linkIssues_400_returnsFalse() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad link"));

        assertThat(client.linkIssues("ES2-1", "ES2-2", "Blocks")).isFalse();
    }

    // ==================== 필드 수정 / 우선순위 ====================

    @Test
    void getPriorities_parsesList() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("[{\"id\":\"1\",\"name\":\"Highest\"},{\"id\":\"3\",\"name\":\"Medium\"}]"));

        var ps = client.getPriorities();
        assertThat(ps).hasSize(2);
        assertThat(ps.get(0).name()).isEqualTo("Highest");
    }

    @Test
    void updateIssueFields_wrapsInFieldsObject() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        boolean ok = client.updateIssueFields("PROJ-1", java.util.Map.of("summary", "새 제목"));

        assertThat(ok).isTrue();
        var req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("PUT");
        assertThat(req.getPath()).contains("/rest/api/3/issue/PROJ-1");
        assertThat(req.getBody().readUtf8()).contains("\"fields\":{\"summary\":\"새 제목\"}");
    }

    @Test
    void updateIssueFields_400_returnsFalse() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));
        assertThat(client.updateIssueFields("PROJ-1", java.util.Map.of("duedate", "2026-07-10"))).isFalse();
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

    @Test
    void issueExists_returnsTrueOn200() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"key\":\"PROJ-1\"}"));
        assertThat(client.issueExists("PROJ-1")).isTrue();
    }

    @Test
    void issueExists_returnsFalseOn404() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"errorMessages\":[\"not found\"]}"));
        assertThat(client.issueExists("PROJ-9")).isFalse();
    }

    @Test
    void issueExists_treatsServerErrorAsExists() {
        // 불확실(5xx)하면 오삭제 방지 위해 존재로 간주.
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThat(client.issueExists("PROJ-2")).isTrue();
    }

    // --- getIssue (이슈 키 조회 카드의 라이브 폴백) ---

    @Test
    void getIssue_parsesFields() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"key":"PROJ-7","fields":{
                          "summary":"로그인 500 에러",
                          "status":{"name":"진행 중","statusCategory":{"name":"진행 중"}},
                          "assignee":{"displayName":"Alice"},
                          "reporter":{"displayName":"Bob"},
                          "issuetype":{"name":"버그","subtask":false},
                          "customfield_10036":3.0}}
                        """));

        var issue = client.getIssue("PROJ-7");

        assertThat(issue).isPresent();
        assertThat(issue.get().key()).isEqualTo("PROJ-7");
        assertThat(issue.get().summary()).isEqualTo("로그인 500 에러");
        assertThat(issue.get().status()).isEqualTo("진행 중");
        assertThat(issue.get().assignee()).isEqualTo("Alice");
        assertThat(issue.get().reporter()).isEqualTo("Bob");
        assertThat(issue.get().storyPoint()).isEqualTo(3.0);
        // sync 와 동일 필드 집합 요청 확인
        String path = server.takeRequest().getPath();
        assertThat(path).contains("/rest/api/3/issue/PROJ-7").contains("fields=");
    }

    @Test
    void getIssue_404_returnsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"errorMessages\":[\"not found\"]}"));
        assertThat(client.getIssue("PROJ-404")).isEmpty();
    }

    // --- assignIssue (담당자 지정) ---

    @Test
    void assignIssue_204_returnsTrueAndSendsAccountId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        boolean ok = client.assignIssue("PROJ-7", "acc-123");

        assertThat(ok).isTrue();
        var req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("PUT");
        assertThat(req.getPath()).isEqualTo("/rest/api/3/issue/PROJ-7/assignee");
        assertThat(req.getBody().readUtf8()).contains("\"accountId\":\"acc-123\"");
    }

    @Test
    void assignIssue_404_returnsFalse() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"errorMessages\":[\"not found\"]}"));
        assertThat(client.assignIssue("PROJ-404", "acc-123")).isFalse();
    }
}
