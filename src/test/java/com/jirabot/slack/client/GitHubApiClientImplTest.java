package com.jirabot.slack.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.BranchResult;
import com.jirabot.slack.config.GitHubProperties;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

class GitHubApiClientImplTest {

    private MockWebServer server;

    private GitHubApiClientImpl client(String token) throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("/").toString();
        var props = new GitHubProperties(token, "CryptoLabInc", List.of("evi"), base);
        WebClient wc = WebClient.builder().baseUrl(base).build();
        return new GitHubApiClientImpl(wc, props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.shutdown();
    }

    private MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).setBody(body);
    }

    @Test
    void createBranch_success() throws IOException {
        GitHubApiClientImpl gh = client("tok");
        server.enqueue(json(200, "{\"default_branch\":\"main\"}"));
        server.enqueue(json(200, "{\"object\":{\"sha\":\"abc123\"}}"));
        server.enqueue(json(201, "{\"ref\":\"refs/heads/feature/ES2-1-foo\"}"));

        BranchResult r = gh.createBranch("evi", "feature/ES2-1-foo");

        assertThat(r.status()).isEqualTo(BranchResult.Status.CREATED);
        assertThat(r.htmlUrl()).isEqualTo("https://github.com/CryptoLabInc/evi/tree/feature/ES2-1-foo");
    }

    @Test
    void createBranch_alreadyExists_returns422AsAlreadyExists() throws IOException {
        GitHubApiClientImpl gh = client("tok");
        server.enqueue(json(200, "{\"default_branch\":\"main\"}"));
        server.enqueue(json(200, "{\"object\":{\"sha\":\"abc123\"}}"));
        server.enqueue(json(422, "{\"message\":\"Reference already exists\"}"));

        BranchResult r = gh.createBranch("evi", "feature/ES2-1-foo");

        assertThat(r.status()).isEqualTo(BranchResult.Status.ALREADY_EXISTS);
    }

    @Test
    void createBranch_disabledWhenTokenBlank_doesNotCallApi() throws IOException {
        GitHubApiClientImpl gh = client("");  // token blank → disabled

        BranchResult r = gh.createBranch("evi", "feature/ES2-1-foo");

        assertThat(r.status()).isEqualTo(BranchResult.Status.FAILED);
        assertThat(server.getRequestCount()).isZero();
    }

    // --- listOpenPullRequests (대시보드 PR 현황) ---

    @Test
    void listOpenPullRequests_parsesFields() throws Exception {
        GitHubApiClientImpl gh = client("tok");
        server.enqueue(json(200, """
                [{"number":42,"title":"ES2-123 로그인 수정","html_url":"https://github.com/x/evi/pull/42",
                  "draft":true,"user":{"login":"yhkim"},
                  "head":{"ref":"bugfix/ES2-123-fix-login"},
                  "created_at":"2026-06-10T01:00:00Z","updated_at":"2026-06-11T02:00:00Z"}]
                """));

        var prs = gh.listOpenPullRequests("evi");

        assertThat(prs).hasSize(1);
        var pr = prs.get(0);
        assertThat(pr.number()).isEqualTo(42);
        assertThat(pr.authorLogin()).isEqualTo("yhkim");
        assertThat(pr.draft()).isTrue();
        assertThat(pr.headRef()).isEqualTo("bugfix/ES2-123-fix-login");
        assertThat(pr.updatedAt()).isEqualTo(java.time.Instant.parse("2026-06-11T02:00:00Z"));
        assertThat(server.takeRequest().getPath()).contains("/pulls?state=open");
    }

    @Test
    void listOpenPullRequests_404_throwsAccessException() throws IOException {
        GitHubApiClientImpl gh = client("tok");
        // fine-grained 토큰 권한 부족 시 GitHub 은 403 이 아닌 404 를 반환.
        server.enqueue(json(404, "{\"message\":\"Not Found\"}"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gh.listOpenPullRequests("evi"))
                .isInstanceOf(GitHubAccessException.class)
                .hasMessageContaining("evi");
    }

    @Test
    void listOpenPullRequests_500_returnsEmpty() throws IOException {
        GitHubApiClientImpl gh = client("tok");
        server.enqueue(json(500, "boom"));

        assertThat(gh.listOpenPullRequests("evi")).isEmpty();
    }
}
