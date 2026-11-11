package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubRepoClientImplTest {

    private MockWebServer server;
    private GitHubRepoClientImpl client;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new GitHubRepoClientImpl(GitHubActionsClientImpl.apiClient(server.url("/").toString(), "tok"), om, "CryptoLabInc");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void listPulls_parsesMergedFlag() throws Exception {
        server.enqueue(json(200, """
                [{"number":5,"title":"t","body":"GO-1","html_url":"u5","head":{"ref":"b"},"merged_at":"2026-11-06T00:00:00Z","merge_commit_sha":"m5"},
                 {"number":6,"title":"t","body":null,"html_url":"u6","head":{"ref":"c"},"merged_at":null}]"""));

        List<GitHubRepoClient.PullInfo> pulls = client.listPulls("HE-KMS", "closed");

        assertThat(pulls).extracting(GitHubRepoClient.PullInfo::merged).containsExactly(true, false);
        assertThat(pulls).extracting(GitHubRepoClient.PullInfo::mergeCommitSha).containsExactly("m5", "");
        assertThat(pulls.get(1).text()).isEqualTo("t\n");
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/CryptoLabInc/HE-KMS/pulls?state=closed&sort=updated&direction=desc&per_page=100");
    }

    @Test
    void pullFiles_listsChangedPaths() throws Exception {
        server.enqueue(json(200, "[{\"filename\":\".github/workflows/gpu.yaml\"},{\"filename\":\"scripts/build.sh\"}]"));

        assertThat(client.pullFiles("evi", 8)).containsExactly(".github/workflows/gpu.yaml", "scripts/build.sh");
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/CryptoLabInc/evi/pulls/8/files?per_page=50");
    }

    @Test
    void createPull_postsJsonBody() throws Exception {
        server.enqueue(json(201, "{\"number\":9,\"title\":\"T\",\"body\":\"B\",\"html_url\":\"u\",\"head\":{\"ref\":\"bot/x\"}}"));

        GitHubRepoClient.PullInfo pr = client.createPull("HE-KMS", "bot/x", "main", "T", "B");

        assertThat(pr.number()).isEqualTo(9);
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        JsonNode sent = om.readTree(req.getBody().readUtf8());
        assertThat(sent.path("head").asText()).isEqualTo("bot/x");
        assertThat(sent.path("base").asText()).isEqualTo("main");
    }

    @Test
    void branchHead_missingIsEmpty_submodulePinOnlyForSubmodules() {
        server.enqueue(json(404, "{\"message\":\"Not Found\"}"));
        assertThat(client.branchHead("HE-KMS", "nope")).isEmpty();

        server.enqueue(json(200, "{\"type\":\"submodule\",\"sha\":\"abc\"}"));
        assertThat(client.submodulePin("envector", "external/HE-KMS", "main")).contains("abc");
        server.enqueue(json(200, "{\"type\":\"dir\",\"sha\":\"def\"}"));
        assertThat(client.submodulePin("envector", "external", "main")).isEmpty();
    }

    @Test
    void contains_isTrueOnlyWhenTheFixIsInThePinsHistory() throws Exception {
        server.enqueue(json(200, "{\"status\":\"ahead\",\"ahead_by\":5,\"behind_by\":0}"));
        server.enqueue(json(200, "{\"status\":\"identical\"}"));
        server.enqueue(json(200, "{\"status\":\"behind\",\"behind_by\":32}"));
        server.enqueue(json(200, "{\"status\":\"diverged\"}"));

        assertThat(client.contains("HE-KMS", "fix", "pin")).isTrue();
        assertThat(client.contains("HE-KMS", "fix", "fix")).isTrue();
        assertThat(client.contains("HE-KMS", "fix", "old")).isFalse();
        assertThat(client.contains("HE-KMS", "fix", "side")).isFalse();
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/CryptoLabInc/HE-KMS/compare/fix...pin?per_page=1");
    }
}
