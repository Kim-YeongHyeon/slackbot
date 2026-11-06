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

    // STUDY: Git Data API 로 clone 없이 커밋을 만든다 — base 커밋의 tree 위에 gitlink(mode 160000, type commit)
    //        한 항목만 바꾼 새 tree → 그 tree 를 가리키는 커밋 → 새 브랜치 ref. 서브모듈 고정값 갱신에만 쓴다.
    @Override
    public String commitGitlink(String repo, String base, String path, String newSha, String message, String newBranch) {
        String baseSha = branchHead(repo, base).orElseThrow(() -> new GitHubActionsException(404, "브랜치 없음: " + base));
        String baseTree = call(HttpMethod.GET, "/repos/{org}/{repo}/git/commits/{sha}", null, org, repo, baseSha)
                .path("tree").path("sha").asText();
        String tree = call(HttpMethod.POST, "/repos/{org}/{repo}/git/trees", Map.of("base_tree", baseTree,
                "tree", List.of(Map.of("path", path, "mode", "160000", "type", "commit", "sha", newSha))), org, repo)
                .path("sha").asText();
        String commit = call(HttpMethod.POST, "/repos/{org}/{repo}/git/commits",
                Map.of("message", message, "tree", tree, "parents", List.of(baseSha)), org, repo).path("sha").asText();
        call(HttpMethod.POST, "/repos/{org}/{repo}/git/refs", Map.of("ref", "refs/heads/" + newBranch, "sha", commit), org, repo);
        return commit;
    }

    private static PullInfo pull(JsonNode p) {
        return new PullInfo(p.path("number").asInt(), p.path("title").asText(""), p.path("body").asText(""),
                p.path("html_url").asText(""), p.path("head").path("ref").asText(""),
                !p.path("merged_at").isMissingNode() && !p.path("merged_at").isNull());
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
