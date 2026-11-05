package com.jirabot.slack.cialert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
public class GitHubActionsClientImpl implements GitHubActionsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_COMMIT_FILES = 30;

    private final WebClient api;
    // STUDY: 로그·아티팩트 API 는 서명된 저장소 URL(blob)로 302 를 준다. 그 URL 에는 GitHub 토큰을
    //        보내면 안 되므로(토큰이 외부 저장소로 샌다) 인증 헤더가 없는 별도 클라이언트로 받는다.
    //        main-ci-failure-alert.yaml 이 NoRedirect 핸들러로 하는 것과 같은 처리.
    private final WebClient blob;
    private final ObjectMapper objectMapper;
    private final String org;

    @Autowired
    public GitHubActionsClientImpl(CiAlertProperties props, ObjectMapper objectMapper) {
        this(apiClient(props.apiBaseUrl(), props.githubToken()), blobClient(), objectMapper, props.org());
    }

    GitHubActionsClientImpl(WebClient api, WebClient blob, ObjectMapper objectMapper, String org) {
        this.api = api;
        this.blob = blob;
        this.objectMapper = objectMapper;
        this.org = org;
    }

    static WebClient apiClient(String baseUrl, String token) {
        WebClient.Builder b = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                // STUDY: followRedirect(false) — Reactor Netty 기본값이지만 의도를 드러내려고 명시.
                //        리다이렉트를 자동으로 따라가면 Authorization 헤더가 blob URL 로 함께 갈 수 있다.
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(TIMEOUT)
                        .followRedirect(false)));
        if (token != null && !token.isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return b.build();
    }

    static WebClient blobClient() {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                        .responseTimeout(LOG_TIMEOUT)))
                .build();
    }

    @Override
    public RunInfo getRun(String repo, long runId) {
        JsonNode r = getJson("/repos/{org}/{repo}/actions/runs/{id}", org, repo, runId);
        JsonNode commit = r.path("head_commit");
        return new RunInfo(r.path("id").asLong(), r.path("name").asText(""), r.path("workflow_id").asLong(),
                r.path("head_sha").asText(""), r.path("head_branch").asText(""), r.path("event").asText(""),
                r.path("conclusion").asText(""), Math.max(1, r.path("run_attempt").asInt(1)),
                r.path("html_url").asText(""), commit.path("message").asText(""),
                commit.path("author").path("name").asText(""));
    }

    @Override
    public List<JobInfo> listJobs(String repo, long runId, int attempt) {
        List<JobInfo> jobs = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode batch = getJson("/repos/{org}/{repo}/actions/runs/{id}/attempts/{a}/jobs?per_page=100&page={p}",
                    org, repo, runId, attempt, page).path("jobs");
            for (JsonNode j : batch) {
                List<StepInfo> steps = new ArrayList<>();
                for (JsonNode s : j.path("steps")) {
                    steps.add(new StepInfo(s.path("name").asText(""), s.path("conclusion").asText("")));
                }
                jobs.add(new JobInfo(j.path("id").asLong(), j.path("name").asText(""),
                        j.path("conclusion").asText(""), List.copyOf(steps)));
            }
            if (batch.size() < 100) {
                break;
            }
        }
        return jobs;
    }

    @Override
    public String downloadJobLogTail(String repo, long jobId, int maxBytes) {
        try {
            byte[] bytes = api.get().uri("/repos/{org}/{repo}/actions/jobs/{id}/logs", org, repo, jobId)
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().is3xxRedirection()) {
                            String location = resp.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                            if (location == null || location.isBlank()) {
                                return resp.releaseBody().then(Mono.error(
                                        new GitHubActionsException(resp.statusCode().value(), "로그 위치 없음")));
                            }
                            // STUDY: URI.create — 서명된 URL 의 쿼리(이미 인코딩됨)를 WebClient 가 다시
                            //        인코딩하지 않게 문자열 템플릿 대신 URI 객체로 넘긴다.
                            return resp.releaseBody().then(blob.get().uri(URI.create(location))
                                    .exchangeToMono(b -> tailOrError(b, maxBytes)));
                        }
                        return tailOrError(resp, maxBytes);
                    })
                    .block(LOG_TIMEOUT);
            return decodeTail(bytes == null ? new byte[0] : bytes, maxBytes);
        } catch (GitHubActionsException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubActionsException("로그 다운로드 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public CommitInfo getCommit(String repo, String sha) {
        JsonNode c = getJson("/repos/{org}/{repo}/commits/{sha}", org, repo, sha);
        List<String> files = new ArrayList<>();
        for (JsonNode f : c.path("files")) {
            if (files.size() >= MAX_COMMIT_FILES) {
                break;
            }
            files.add(f.path("filename").asText(""));
        }
        return new CommitInfo(c.path("sha").asText(sha), c.path("commit").path("message").asText(""),
                c.path("commit").path("author").path("name").asText(""), List.copyOf(files));
    }

    @Override
    public List<RunSummary> recentRuns(String repo, long workflowId, String branch, int count) {
        JsonNode runs = getJson("/repos/{org}/{repo}/actions/workflows/{wid}/runs?branch={b}&per_page={n}"
                + "&exclude_pull_requests=true", org, repo, workflowId, branch, count).path("workflow_runs");
        List<RunSummary> out = new ArrayList<>();
        for (JsonNode r : runs) {
            out.add(new RunSummary(r.path("id").asLong(), r.path("conclusion").asText(""),
                    r.path("head_sha").asText("")));
        }
        return out;
    }

    @Override
    public List<ArtifactInfo> listArtifacts(String repo, long runId) {
        JsonNode arts = getJson("/repos/{org}/{repo}/actions/runs/{id}/artifacts?per_page=100", org, repo, runId)
                .path("artifacts");
        List<ArtifactInfo> out = new ArrayList<>();
        for (JsonNode a : arts) {
            out.add(new ArtifactInfo(a.path("id").asLong(), a.path("name").asText(""),
                    a.path("size_in_bytes").asLong(), a.path("expired").asBoolean(false)));
        }
        return out;
    }

    @Override
    public byte[] downloadArtifactZip(String repo, long artifactId, int maxBytes) {
        try {
            byte[] bytes = api.get().uri("/repos/{org}/{repo}/actions/artifacts/{id}/zip", org, repo, artifactId)
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().is3xxRedirection()) {
                            String location = resp.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
                            if (location == null || location.isBlank()) {
                                return resp.releaseBody().then(Mono.error(
                                        new GitHubActionsException(resp.statusCode().value(), "아티팩트 위치 없음")));
                            }
                            return resp.releaseBody().then(blob.get().uri(URI.create(location))
                                    .exchangeToMono(b -> wholeOrError(b, maxBytes)));
                        }
                        return wholeOrError(resp, maxBytes);
                    })
                    .block(LOG_TIMEOUT);
            return bytes == null ? new byte[0] : bytes;
        } catch (GitHubActionsException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubActionsException("아티팩트 다운로드 실패: " + e.getMessage(), e);
        }
    }

    private JsonNode getJson(String uriTemplate, Object... vars) {
        try {
            String body = api.get().uri(uriTemplate, vars)
                    .exchangeToMono(resp -> resp.statusCode().is2xxSuccessful()
                            ? resp.bodyToMono(String.class).defaultIfEmpty("{}")
                            : errorFrom(resp))
                    .block(TIMEOUT);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (GitHubActionsException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubActionsException("GitHub 요청 실패: " + e.getMessage(), e);
        }
    }

    private static Mono<byte[]> tailOrError(ClientResponse resp, int maxBytes) {
        if (!resp.statusCode().is2xxSuccessful()) {
            return errorFrom(resp);
        }
        return tail(resp.bodyToFlux(DataBuffer.class), maxBytes);
    }

    private static <T> Mono<T> errorFrom(ClientResponse resp) {
        int status = resp.statusCode().value();
        return resp.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(body -> Mono.error(new GitHubActionsException(status, "HTTP " + status + " "
                        + (body.length() > 200 ? body.substring(0, 200) : body))));
    }

    private static Mono<byte[]> wholeOrError(ClientResponse resp, int maxBytes) {
        if (!resp.statusCode().is2xxSuccessful()) {
            return errorFrom(resp);
        }
        return resp.bodyToFlux(DataBuffer.class).reduce(new ByteArrayOutputStream(), (acc, buf) -> {
            try {
                byte[] chunk = new byte[buf.readableByteCount()];
                buf.read(chunk);
                if (acc.size() + chunk.length > maxBytes) {
                    throw new GitHubActionsException(413, "아티팩트가 " + (maxBytes / (1024 * 1024)) + "MB 를 넘어요");
                }
                acc.write(chunk, 0, chunk.length);
                return acc;
            } finally {
                DataBufferUtils.release(buf);
            }
        }).map(ByteArrayOutputStream::toByteArray).defaultIfEmpty(new byte[0]);
    }

    // STUDY: 로그는 수십 MB 일 수 있어 전체를 메모리에 올리지 않고, 흘려보내면서 마지막 maxBytes 만 남긴다.
    //        DataBuffer 는 풀링된 버퍼라 읽은 뒤 release 해야 메모리가 새지 않는다.
    static Mono<byte[]> tail(Flux<DataBuffer> body, int maxBytes) {
        return body.reduce(new TailBuffer(maxBytes), (acc, buf) -> {
            try {
                acc.append(buf);
            } finally {
                DataBufferUtils.release(buf);
            }
            return acc;
        }).map(TailBuffer::bytes).defaultIfEmpty(new byte[0]);
    }

    // 잘린 경우 첫 줄은 중간부터 시작하므로 버린다.
    static String decodeTail(byte[] bytes, int maxBytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length >= maxBytes) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
        }
        return s;
    }

    static final class TailBuffer {
        private final int max;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        TailBuffer(int max) {
            this.max = max;
        }

        void append(DataBuffer buf) {
            byte[] chunk = new byte[buf.readableByteCount()];
            buf.read(chunk);
            out.write(chunk, 0, chunk.length);
            if (out.size() > max * 2) {
                byte[] kept = bytes();
                out.reset();
                out.write(kept, 0, kept.length);
            }
        }

        byte[] bytes() {
            byte[] all = out.toByteArray();
            if (all.length <= max) {
                return all;
            }
            byte[] last = new byte[max];
            System.arraycopy(all, all.length - max, last, 0, max);
            return last;
        }
    }
}
