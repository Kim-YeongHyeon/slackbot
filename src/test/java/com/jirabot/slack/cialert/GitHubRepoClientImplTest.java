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
                [{"number":5,"title":"t","body":"GO-1","html_url":"u5","head":{"ref":"b"},"merged_at":"2026-11-06T00:00:00Z"},
                 {"number":6,"title":"t","body":null,"html_url":"u6","head":{"ref":"c"},"merged_at":null}]"""));

        List<GitHubRepoClient.PullInfo> pulls = client.listPulls("HE-KMS", "closed");

        assertThat(pulls).extracting(GitHubRepoClient.PullInfo::merged).containsExactly(true, false);
        assertThat(pulls.get(1).text()).isEqualTo("t\n");
        assertThat(server.takeRequest().getPath()).isEqualTo("/repos/CryptoLabInc/HE-KMS/pulls?state=closed&sort=updated&direction=desc&per_page=100");
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
    void commitGitlink_buildsTreeCommitAndRef() throws Exception {
        server.enqueue(json(200, "{\"object\":{\"sha\":\"base\"}}"));
        server.enqueue(json(200, "{\"tree\":{\"sha\":\"basetree\"}}"));
        server.enqueue(json(201, "{\"sha\":\"newtree\"}"));
        server.enqueue(json(201, "{\"sha\":\"newcommit\"}"));
        server.enqueue(json(201, "{\"ref\":\"refs/heads/bot/pin\"}"));

        String commit = client.commitGitlink("envector", "main", "external/HE-KMS", "f".repeat(40), "msg", "bot/pin");

        assertThat(commit).isEqualTo("newcommit");
        server.takeRequest();
        server.takeRequest();
        JsonNode tree = om.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(tree.path("base_tree").asText()).isEqualTo("basetree");
        assertThat(tree.path("tree").get(0).path("mode").asText()).isEqualTo("160000");
        assertThat(tree.path("tree").get(0).path("type").asText()).isEqualTo("commit");
        JsonNode c = om.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(c.path("parents").get(0).asText()).isEqualTo("base");
        JsonNode ref = om.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(ref.path("ref").asText()).isEqualTo("refs/heads/bot/pin");
        assertThat(ref.path("sha").asText()).isEqualTo("newcommit");
    }
}
