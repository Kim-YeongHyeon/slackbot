package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubActionsClient.JobInfo;
import java.io.IOException;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubActionsClientImplTest {

    private MockWebServer server;
    private GitHubActionsClientImpl client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("/").toString();
        client = new GitHubActionsClientImpl(GitHubActionsClientImpl.apiClient(base, "tok"),
                GitHubActionsClientImpl.blobClient(), new ObjectMapper(), "CryptoLabInc");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void getRun_parsesFieldsAndSendsToken() throws Exception {
        server.enqueue(json(200, """
                {"id":7,"name":"evi main","workflow_id":99,"head_sha":"abc123","head_branch":"main",
                 "event":"push","conclusion":"failure","run_attempt":2,"html_url":"https://x/7",
                 "head_commit":{"message":"Fix it\\n\\nbody","author":{"name":"dev"}}}"""));

        GitHubActionsClient.RunInfo run = client.getRun("evi", 7);

        assertThat(run.name()).isEqualTo("evi main");
        assertThat(run.workflowId()).isEqualTo(99);
        assertThat(run.runAttempt()).isEqualTo(2);
        assertThat(run.commitAuthor()).isEqualTo("dev");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/repos/CryptoLabInc/evi/actions/runs/7");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok");
    }

    @Test
    void listJobs_readsAllPagesAndFailedSteps() throws Exception {
        StringBuilder page1 = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < 100; i++) {
            page1.append(i == 0 ? "" : ",").append("{\"id\":").append(i)
                    .append(",\"name\":\"ok").append(i).append("\",\"conclusion\":\"success\",\"steps\":[]}");
        }
        page1.append("]}");
        server.enqueue(json(200, page1.toString()));
        server.enqueue(json(200, """
                {"jobs":[{"id":500,"name":"test (gpu)","conclusion":"failure",
                  "steps":[{"name":"Checkout","conclusion":"success"},{"name":"Build","conclusion":"failure"}]}]}"""));

        List<JobInfo> jobs = client.listJobs("evi", 7, 1);

        assertThat(jobs).hasSize(101);
        JobInfo failed = jobs.get(100);
        assertThat(failed.failed()).isTrue();
        assertThat(failed.failedStepNames()).containsExactly("Build");
        server.takeRequest();
        assertThat(server.takeRequest().getPath()).contains("/attempts/1/jobs?per_page=100&page=2");
    }

    @Test
    void downloadJobLogTail_followsRedirectWithoutSendingToken() throws Exception {
        String signed = server.url("/blob/job.log").toString() + "?sig=a%2Bb%3D&se=2026";
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", signed));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("line1\nline2\n"));

        String log = client.downloadJobLogTail("evi", 55, 1024);

        assertThat(log).isEqualTo("line1\nline2\n");
        RecordedRequest api = server.takeRequest();
        assertThat(api.getPath()).isEqualTo("/repos/CryptoLabInc/evi/actions/jobs/55/logs");
        assertThat(api.getHeader("Authorization")).isEqualTo("Bearer tok");
        RecordedRequest blob = server.takeRequest();
        // 서명 쿼리는 이중 인코딩 없이 그대로, 토큰은 보내지 않는다.
        assertThat(blob.getPath()).isEqualTo("/blob/job.log?sig=a%2Bb%3D&se=2026");
        assertThat(blob.getHeader("Authorization")).isNull();
    }

    @Test
    void downloadJobLogTail_keepsOnlyTheLastBytesFromALineBoundary() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("log line number ").append(i).append('\n');
        }
        server.enqueue(new MockResponse().setResponseCode(200).setBody(big.toString()));

        String log = client.downloadJobLogTail("evi", 1, 200);

        assertThat(log.length()).isLessThanOrEqualTo(200);
        assertThat(log).startsWith("log line number").endsWith("log line number 499\n");
    }

    @Test
    void forbidden_throwsWithStatus() {
        server.enqueue(json(403, "{\"message\":\"Resource not accessible by personal access token\"}"));

        assertThatThrownBy(() -> client.getRun("evi", 1))
                .isInstanceOf(GitHubActionsException.class)
                .satisfies(e -> assertThat(((GitHubActionsException) e).status()).isEqualTo(403));
    }

    @Test
    void blobError_throwsWithStatus() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/gone").toString()));
        server.enqueue(new MockResponse().setResponseCode(410).setBody("expired"));

        assertThatThrownBy(() -> client.downloadJobLogTail("evi", 1, 100))
                .isInstanceOf(GitHubActionsException.class)
                .satisfies(e -> assertThat(((GitHubActionsException) e).status()).isEqualTo(410));
    }

    @Test
    void getCommit_capsFileList() {
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            files.append(i == 0 ? "" : ",").append("{\"filename\":\"f").append(i).append("\"}");
        }
        server.enqueue(json(200, "{\"sha\":\"abc\",\"commit\":{\"message\":\"m\",\"author\":{\"name\":\"a\"}},"
                + "\"files\":[" + files + "]}"));

        GitHubActionsClient.CommitInfo c = client.getCommit("evi", "abc");

        assertThat(c.files()).hasSize(30).startsWith("f0");
        assertThat(c.author()).isEqualTo("a");
    }

    @Test
    void recentRuns_parsesConclusions() throws Exception {
        server.enqueue(json(200, """
                {"workflow_runs":[{"id":9,"conclusion":"failure","head_sha":"s9"},
                                  {"id":8,"conclusion":"success","head_sha":"s8"}]}"""));

        List<GitHubActionsClient.RunSummary> runs = client.recentRuns("evi", 99, "main", 10);

        assertThat(runs).extracting(GitHubActionsClient.RunSummary::conclusion).containsExactly("failure", "success");
        assertThat(server.takeRequest().getPath())
                .isEqualTo("/repos/CryptoLabInc/evi/actions/workflows/99/runs?branch=main&per_page=10&exclude_pull_requests=true");
    }
}
