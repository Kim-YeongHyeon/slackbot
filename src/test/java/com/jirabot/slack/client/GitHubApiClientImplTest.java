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
}
