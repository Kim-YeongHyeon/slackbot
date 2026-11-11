package com.jirabot.slack.cialert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class GitHubRepoClientImpl implements GitHubRepoClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient api;
    private final ObjectMapper objectMapper;
    private final String org;

    @Autowired
    public GitHubRepoClientImpl(CiAlertProperties props, ObjectMapper objectMapper) {
        this(GitHubActionsClientImpl.apiClient(props.apiBaseUrl(), props.githubToken()), objectMapper, props.org());
    }

    GitHubRepoClientImpl(WebClient api, ObjectMapper objectMapper, String org) {
        this.api = api;
        this.objectMapper = objectMapper;
        this.org = org;
    }

    @Override
    public List<PullInfo> listPulls(String repo, String state) {
        JsonNode arr = call(HttpMethod.GET, "/repos/{org}/{repo}/pulls?state={s}&sort=updated&direction=desc&per_page=100",
                null, org, repo, state);
        List<PullInfo> out = new ArrayList<>();
        arr.forEach(p -> out.add(pull(p)));
        return out;
    }

    @Override
    public PullInfo createPull(String repo, String head, String base, String title, String body) {
        return pull(call(HttpMethod.POST, "/repos/{org}/{repo}/pulls",
                Map.of("head", head, "base", base, "title", title, "body", body), org, repo));
    }

    @Override
    public List<String> pullFiles(String repo, int number) {
        List<String> out = new ArrayList<>();
        call(HttpMethod.GET, "/repos/{org}/{repo}/pulls/{n}/files?per_page=50", null, org, repo, number)
                .forEach(f -> out.add(f.path("filename").asText("")));
        return out;
    }

    @Override
    public String defaultBranch(String repo) {
        return call(HttpMethod.GET, "/repos/{org}/{repo}", null, org, repo).path("default_branch").asText("main");
    }

    @Override
    public Optional<String> branchHead(String repo, String branch) {
        try {
            return Optional.of(call(HttpMethod.GET, "/repos/{org}/{repo}/git/ref/heads/{b}", null, org, repo, branch)
                    .path("object").path("sha").asText());
        } catch (GitHubActionsException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<String> submodulePin(String repo, String path, String ref) {
        try {
            JsonNode c = call(HttpMethod.GET, "/repos/{org}/{repo}/contents/{path}?ref={ref}", null, org, repo, path, ref);
            return "submodule".equals(c.path("type").asText()) ? Optional.of(c.path("sha").asText()) : Optional.empty();
        } catch (GitHubActionsException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // STUDY: compare API 의 status — head 가 base 를 포함하면 ahead, 같으면 identical, 아니면 behind/diverged.
    //        "수정 커밋(base)이 서브모듈 핀(head)에 들어 있나"를 커밋 목록을 받지 않고 한 번에 판단한다.
    @Override
    public boolean contains(String repo, String ancestorSha, String sha) {
        String status = call(HttpMethod.GET, "/repos/{org}/{repo}/compare/{base}...{head}?per_page=1", null,
                org, repo, ancestorSha, sha).path("status").asText("");
        return "ahead".equals(status) || "identical".equals(status);
    }

    private static PullInfo pull(JsonNode p) {
        return new PullInfo(p.path("number").asInt(), p.path("title").asText(""), p.path("body").asText(""),
                p.path("html_url").asText(""), p.path("head").path("ref").asText(""),
                !p.path("merged_at").isMissingNode() && !p.path("merged_at").isNull(),
                p.path("merge_commit_sha").asText(""));
    }

    private JsonNode call(HttpMethod method, String uri, Object body, Object... vars) {
        try {
            WebClient.RequestBodySpec spec = api.method(method).uri(uri, vars);
            WebClient.RequestHeadersSpec<?> req = body == null ? spec
                    : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(objectMapper.writeValueAsString(body));
            String out = req.exchangeToMono(resp -> resp.statusCode().is2xxSuccessful()
                            ? resp.bodyToMono(String.class).defaultIfEmpty("{}")
                            : resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(b -> Mono.error(
                                    new GitHubActionsException(resp.statusCode().value(), "HTTP " + resp.statusCode().value()
                                            + " " + (b.length() > 300 ? b.substring(0, 300) : b)))))
                    .block(TIMEOUT);
            return objectMapper.readTree(out == null ? "{}" : out);
        } catch (GitHubActionsException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubActionsException("GitHub 요청 실패: " + e.getMessage(), e);
        }
    }
}
