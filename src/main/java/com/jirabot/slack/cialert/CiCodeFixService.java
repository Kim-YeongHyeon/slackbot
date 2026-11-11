package com.jirabot.slack.cialert;

import static com.jirabot.slack.cialert.CiReplyRenderer.escape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubActionsClient.RunInfo;
import com.jirabot.slack.cialert.GitHubRepoClient.PullInfo;
import com.jirabot.slack.entity.CiAlertEntity;
import com.jirabot.slack.repository.CiAlertRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: 연속 실패 자동 수정 (v0.0.100). 같은 워크플로가 3번 이상 연속 실패하면 분석 답글 뒤에:
//        1) 봇이 올린 열린 PR(본문 표식) → 링크   2) 24시간 안에 이미 시도 → 그 결과를 다시 보여 줌
//        3) 그 뒤 실행이 성공 → 건너뜀            4) Opus 에이전트(컨테이너)가 레포를 보며 원인을 다시 분석
//        에이전트는 "이미 고치는 PR 이 있다 / 레포 수정으로 못 고친다 / 고쳤다" 중 하나로 답하고,
//        고쳤으면 DiffGuard(Policy.code) → 커밋·push → PR. SbomFixService 와 같은 구조이고 자동 머지는 없다.
//        SBOM 과 달리 이 서버에서 수정 결과를 다시 검사할 수 없어, 검증은 그 PR 의 CI 가 한다.
@Service
public class CiCodeFixService {

    private static final Logger log = LoggerFactory.getLogger(CiCodeFixService.class);
    static final String MARKER = "sol-ci-fix";
    static final String AUTHOR = "sol-ci-bot";
    private static final int MAX_ROOT_CAUSE = 1500;
    private static final int MAX_DIFF = 2500;
    private static final int MAX_PR_BODY_EXCERPT = 300;
    private static final int MAX_PR_FILES = 30;

    private final CiFixProperties fixProps;
    private final CiCodeFixProperties codeProps;
    private final GitHubRepoClient repos;
    private final CiAlertRepository alerts;
    private final FixWorkspace workspace;
    private final FixAgentRunner agent;
    private final DiffGuard guard;
    private final ObjectMapper objectMapper;

    public CiCodeFixService(CiFixProperties fixProps, CiCodeFixProperties codeProps,
                            GitHubRepoClient repos, CiAlertRepository alerts, FixWorkspace workspace,
                            FixAgentRunner agent, DiffGuard guard, ObjectMapper objectMapper) {
        this.fixProps = fixProps;
        this.codeProps = codeProps;
        this.repos = repos;
        this.alerts = alerts;
        this.workspace = workspace;
        this.agent = agent;
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    // PATCH: 워크플로 파일이라 봇 토큰으로 push 하지 못해 diff 만 답글로. NOT_FIXABLE: 원인이 레포 밖.
    public enum Status { CREATED, EXISTS, PATCH, NOT_FIXABLE, SKIPPED, DRY_RUN, FAILED }

    // channel: 반복 판단과 같은 구분 (실제 알림 채널 또는 "DM:<user>"). force: 재처리에서 수정까지 요청함.
    public record Request(long alertId, String channel, CiAlert alert, RunInfo run, int streak, boolean recovered,
                          String fingerprint, boolean dryRun, boolean force) {}

    // label: 링크에 쓸 "#12 제목". detail: 상태별 부연 (변경 요약 · diff · 실패 이유).
    // reused: 앞선 시도의 결과를 다시 보여 주는 것 (에이전트를 돌리지 않음).
    public record Result(Status status, String url, String label, String rootCause, String detail, List<String> steps,
                         boolean reused) {
        static Result of(Status status, String detail) {
            return new Result(status, "", "", "", detail, List.of(), false);
        }
    }

    public boolean enabled() {
        return codeProps.enabled() && !fixProps.oauthToken().isBlank();
    }

    // 실제 알림은 연속 실패 횟수로, 재처리(dry-run 포함)는 요청했을 때만 돈다.
    public boolean wants(int streak, boolean dryRun, boolean force) {
        return enabled() && (force || (!dryRun && streak >= codeProps.minStreak()));
    }

    // 같은 워크플로·브랜치를 가리키는 키 — PR 본문 표식과 DB 의 fix_key 에 쓴다.
    public String keyOf(Request req) {
        return key(req.alert().repo(), req.run(), branch(req));
    }

    static String key(String repo, RunInfo run, String branch) {
        return repo.toLowerCase(Locale.ROOT) + "/" + run.workflowId() + "@" + branch;
    }

    // 1~3단계: 에이전트 없이 끝나는 경우. 비어 있으면 fix() 를 돌려야 한다.
    public Optional<Result> existing(Request req) {
        String repo = req.alert().repo();
        String key = key(repo, req.run(), branch(req));
        Optional<PullInfo> mine = repos.listPulls(repo, "open").stream()
                .filter(p -> p.body() != null && p.body().contains(marker(key))).findFirst();
        if (mine.isPresent()) {
            PullInfo p = mine.get();
            return Optional.of(new Result(Status.EXISTS, p.htmlUrl(), "#" + p.number() + " " + p.title(), "", "", List.of(), false));
        }
        if (req.force()) {
            return Optional.empty();
        }
        Instant since = Instant.now().minus(Duration.ofHours(codeProps.cooldownHours()));
        Optional<CiAlertEntity> prior = alerts
                .findFirstByChannelAndFixKeyAndFixStatusIsNotNullAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                        req.channel(), key, since, req.alertId());
        if (prior.isPresent()) {
            return Optional.of(reuse(prior.get()));
        }
        if (req.recovered()) {
            return Optional.of(Result.of(Status.SKIPPED, "이 알림 뒤의 실행이 이미 성공했어요"));
        }
        return Optional.empty();
    }

    private static Result reuse(CiAlertEntity prior) {
        Status status;
        try {
            status = Status.valueOf(prior.getFixStatus());
        } catch (IllegalArgumentException e) {
            status = Status.FAILED;
        }
        String url = prior.getPrUrls() == null ? "" : prior.getPrUrls().split(",")[0];
        String label = url.contains("/pull/") ? "#" + url.substring(url.lastIndexOf('/') + 1) : "";
        String detail = prior.getFixDetail() == null ? "" : prior.getFixDetail();
        return new Result(status, url, label, "", detail, List.of(), true);
    }

    // 4단계: 에이전트. input 은 분석에 쓴 것과 같은 실패 정보(로그 발췌 포함).
    public Result fix(Request req, CiFailureAnalyzer.Input input, String firstCategory, String firstSummary) {
        String repo = req.alert().repo();
        String branch = branch(req);
        if (branch.startsWith("bot/")) {
            return Result.of(Status.SKIPPED, "봇이 만든 브랜치(" + branch + ")의 실패예요");
        }
        if (repos.branchHead(repo, branch).isEmpty()) {
            return Result.of(Status.SKIPPED, "브랜치 " + branch + " 가 없어요");
        }
        String key = key(repo, req.run(), branch);
        List<PullInfo> open = repos.listPulls(repo, "open").stream().limit(codeProps.maxOpenPrs()).toList();
        String jobId = req.alertId() + "-cifix-" + repo.toLowerCase(Locale.ROOT) + "-" + (System.currentTimeMillis() % 100000);
        agent.ensureImage();
        FixWorkspace.Workspace ws = workspace.prepare(repo, jobId, branch);
        try {
            FixAgentRunner.Raw raw = agent.runRaw(ws, task(req, branch, input, firstCategory, firstSummary, open), jobId,
                    new FixAgentRunner.AgentSpec(codeProps.model(), codeProps.effort(), codeProps.promptFile(),
                            codeProps.maxTurns(), codeProps.timeoutMinutes(), List.of("GOTOOLCHAIN=auto")));
            if (!raw.ok()) {
                return Result.of(Status.FAILED, raw.error());
            }
            JsonNode out = raw.out();
            String rootCause = out.path("root_cause").asText("").strip();
            return switch (out.path("outcome").asText("")) {
                case "existing_pr" -> existingPr(out.path("existing_pr").asInt(-1), open, rootCause);
                case "not_fixable" -> new Result(Status.NOT_FIXABLE, "", "", rootCause, out.path("notes").asText(""),
                        strings(out.path("manual_steps")), false);
                case "fixed" -> publish(req, ws, branch, key, input, out, rootCause);
                default -> new Result(Status.FAILED, "", "", rootCause, "에이전트 응답을 이해하지 못했어요", List.of(), false);
            };
        } finally {
            workspace.cleanup(ws);
        }
    }

    // 에이전트가 가리킨 번호가 봇이 보여 준 목록에 있을 때만 링크한다 (없는 PR 링크를 만들지 않게).
    private static Result existingPr(int number, List<PullInfo> open, String rootCause) {
        return open.stream().filter(p -> p.number() == number).findFirst()
                .map(p -> new Result(Status.EXISTS, p.htmlUrl(), "#" + p.number() + " " + p.title(), rootCause, "", List.of(), false))
                .orElseGet(() -> new Result(Status.FAILED, "", "", rootCause,
                        "에이전트가 열린 PR 목록에 없는 #" + number + " 을 가리켰어요", List.of(), false));
    }

    private Result publish(Request req, FixWorkspace.Workspace ws, String branch, String key,
                           CiFailureAnalyzer.Input input, JsonNode out, String rootCause) {
        DiffGuard.Verdict verdict = guard.check(ws, DiffGuard.Policy.code(codeProps.maxDiffLines(), codeProps.maxFiles()));
        if (!verdict.ok()) {
            return new Result(Status.FAILED, "", "", rootCause, verdict.reason(), List.of(), false);
        }
        String summary = out.path("summary").asText("").strip();
        String changed = "파일 " + String.join(", ", verdict.files()) + " (" + verdict.changedLines() + "줄)";
        if (req.dryRun()) {
            return new Result(Status.DRY_RUN, "", "", rootCause,
                    changed + "\n요약: " + summary + "\n검증: " + out.path("verification").asText("") + fence(verdict.diff()),
                    List.of(), false);
        }
        String repo = req.alert().repo();
        String title = title(out.path("title").asText(""), req.run().name());
        String head = freeBranch(repo, "bot/ci-fix-" + slug(req.run().name()) + "-" + shortFp(req.fingerprint()));
        try {
            workspace.commitAndPush(ws, verdict.files(), head, title + "\n\n" + req.alert().runUrl(), AUTHOR);
        } catch (RuntimeException e) {
            // STUDY: GitHub 는 워크플로 파일을 바꾸는 push 를 토큰의 Workflows 권한으로 따로 막는다.
            //        권한이 없으면 PR 대신 스레드에 diff 를 달아 사람이 적용하게 한다.
            if (verdict.files().stream().anyMatch(f -> f.startsWith(".github/workflows/"))) {
                log.info("ci code fix push rejected for workflow files repo={}: {}", repo, e.getMessage());
                return new Result(Status.PATCH, "", "", rootCause, summary + "\n" + changed + fence(verdict.diff()), List.of(), false);
            }
            throw e;
        }
        PullInfo pr = repos.createPull(repo, head, branch, title, body(req, branch, key, input, verdict, out, rootCause));
        log.info("ci code fix PR created repo={} url={}", repo, pr.htmlUrl());
        return new Result(Status.CREATED, pr.htmlUrl(), "#" + pr.number() + " " + title, rootCause, summary, List.of(), false);
    }

    String task(Request req, String branch, CiFailureAnalyzer.Input input, String firstCategory, String firstSummary,
                List<PullInfo> open) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("repo", req.alert().repo());
        task.put("workflow", req.run().name());
        task.put("workflow_file", req.run().path());
        task.put("branch", branch);
        task.put("run_url", req.alert().runUrl());
        task.put("consecutive_failures", req.streak());
        if (firstSummary != null && !firstSummary.isBlank()) {
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("category", firstCategory == null ? "" : firstCategory);
            first.put("summary", firstSummary);
            task.put("first_analysis", first);
        }
        List<Map<String, Object>> prs = new ArrayList<>();
        for (PullInfo p : open) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", p.number());
            m.put("title", p.title());
            m.put("branch", p.headRef());
            m.put("body", cut(p.body(), MAX_PR_BODY_EXCERPT));
            m.put("files", filesOf(req.alert().repo(), p.number()));
            prs.add(m);
        }
        task.put("open_prs", prs);
        try {
            return "Investigate this repeated CI failure. Task:\n" + objectMapper.writeValueAsString(task) + "\n\n"
                    + CiFailureAnalyzer.buildInput(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> filesOf(String repo, int number) {
        try {
            return repos.pullFiles(repo, number).stream().limit(MAX_PR_FILES).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static String title(String agentTitle, String workflow) {
        String t = agentTitle == null ? "" : agentTitle.strip().replaceAll("[\\r\\n]+", " ");
        if (!t.matches("^(?:fix|ci|build|chore|test|revert)(?:\\([^)]*\\))?: .+")) {
            t = "fix(ci): " + workflow + " 연속 실패 수정";
        }
        return t.length() > 100 ? t.substring(0, 99) + "…" : t;
    }

    static String body(Request req, String branch, String key, CiFailureAnalyzer.Input input, DiffGuard.Verdict verdict,
                       JsonNode out, String rootCause) {
        StringBuilder sb = new StringBuilder("## 문제\n");
        sb.append("`").append(req.run().name()).append("` 워크플로가 `").append(branch).append("` 에서 ")
                .append(req.streak()).append("번 연속 실패했습니다 ([run](").append(req.alert().runUrl()).append(")).\n");
        for (CiFailureAnalyzer.FailedJob j : input.failedJobs()) {
            sb.append("- 실패 job `").append(j.name()).append('`');
            if (!j.failedSteps().isEmpty()) {
                sb.append(" → step ").append(String.join(", ", j.failedSteps().stream().map(s -> "`" + s + "`").toList()));
            }
            sb.append('\n');
        }
        sb.append("\n## 원인\n").append(rootCause.isBlank() ? "(에이전트가 원인을 적지 않았습니다)" : rootCause).append('\n');
        sb.append("\n## 채택한 해결 방안\n").append(out.path("summary").asText("")).append('\n');
        sb.append("\n변경 파일: ").append(String.join(", ", verdict.files().stream().map(f -> "`" + f + "`").toList()))
                .append(" (").append(verdict.changedLines()).append("줄)\n");
        sb.append("\n## 검증 방법\n");
        String verification = out.path("verification").asText("");
        if (!verification.isBlank()) {
            sb.append("- 에이전트: ").append(verification).append('\n');
        }
        sb.append("- 봇 서버에서는 이 워크플로를 다시 돌릴 수 없습니다. 이 PR 의 CI 결과로 확인해 주세요.\n");
        String notes = out.path("notes").asText("");
        if (!notes.isBlank()) {
            sb.append("\n## 참고\n").append(notes).append('\n');
        }
        sb.append("\n---\n🤖 #sol-dev-alarm 알림 봇이 자동으로 만든 PR 입니다 (Claude 에이전트가 분석·수정, 봇이 변경 범위를 확인). 자동 머지는 하지 않습니다.\n");
        sb.append("<!-- ").append(marker(key)).append("-->\n");
        return sb.toString();
    }

    // 끝의 공백까지가 표식 — key 가 다른 key 의 앞부분과 겹쳐도 구분된다.
    static String marker(String key) {
        return MARKER + " key=" + key + " ";
    }

    // 결과 답글 (스레드). 빈 문자열이면 답글을 달지 않는다. 링크는 Slack 형식, 나머지는 이스케이프.
    public static String render(Result r, int streak) {
        if (r.reused()) {
            return switch (r.status()) {
                case CREATED, EXISTS -> r.url().isBlank() ? ""
                        : "🔧 수정 PR <" + r.url() + "|" + escape(r.label()) + "> 이 올라와 있어요 — 머지되면 해결돼요";
                case PATCH -> "🔧 앞서 제안한 워크플로 수정이 아직 적용되지 않았어요" + line(r.detail());
                case NOT_FIXABLE -> "🔧 레포 수정으로는 고칠 수 없는 실패예요" + line(r.detail());
                default -> "";
            };
        }
        StringBuilder sb = new StringBuilder("🔧 *자동 수정*");
        if (streak >= 2) {
            sb.append(" · ").append(streak).append("번 연속 실패라 원인을 다시 봤어요");
        }
        sb.append('\n');
        if (!r.rootCause().isBlank()) {
            sb.append("*원인* ").append(escape(cut(r.rootCause(), MAX_ROOT_CAUSE))).append('\n');
        }
        switch (r.status()) {
            case CREATED -> sb.append("*수정 PR* <").append(r.url()).append("|").append(escape(r.label())).append(">")
                    .append(line(r.detail()));
            case EXISTS -> sb.append("🔁 이미 올라와 있는 PR <").append(r.url()).append("|").append(escape(r.label()))
                    .append("> — 머지되면 해결돼요");
            case PATCH -> sb.append("⚠️ 워크플로 파일 수정이라 봇 토큰으로는 push 하지 못했어요 ")
                    .append("(GITHUB_ALERT_TOKEN 에 Workflows 권한 필요). 아래 수정을 직접 적용해 주세요.\n").append(escape(r.detail()));
            case NOT_FIXABLE -> {
                sb.append("*레포 수정으로는 고칠 수 없어요*");
                if (!r.steps().isEmpty()) {
                    sb.append(" — 필요한 조치\n");
                    r.steps().stream().limit(4).forEach(s -> sb.append("• ").append(escape(cut(s, 400))).append('\n'));
                }
                if (!r.detail().isBlank()) {
                    sb.append('\n').append(escape(cut(r.detail(), 600)));
                }
            }
            case DRY_RUN -> sb.append("🧪 dry-run (push 안 함)\n").append(escape(r.detail()));
            case SKIPPED -> sb.append("건너뜀: ").append(escape(r.detail()));
            case FAILED -> sb.append("⚠️ 수정 PR 을 만들지 못했어요: ").append(escape(r.detail()));
        }
        return sb.toString().strip();
    }

    // DB 에 남길 한 줄 (다시 보여 줄 때 쓴다)
    public static String record(Result r) {
        String src = r.rootCause().isBlank() ? r.detail() : r.rootCause();
        return cut(src.lines().findFirst().orElse(""), 300);
    }

    private String branch(Request req) {
        String b = req.run().headBranch();
        return b == null || b.isBlank() ? repos.defaultBranch(req.alert().repo()) : b;
    }

    private String freeBranch(String repo, String base) {
        String b = base;
        for (int i = 2; repos.branchHead(repo, b).isPresent(); i++) {
            b = base + "-" + i;
        }
        return b;
    }

    static String slug(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (t.isEmpty()) {
            return "workflow";
        }
        return t.length() > 40 ? t.substring(0, 40).replaceAll("-+$", "") : t;
    }

    private static String shortFp(String fingerprint) {
        return fingerprint == null || fingerprint.isBlank() ? "x" : fingerprint.substring(0, Math.min(8, fingerprint.length()));
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                if (!n.asText("").isBlank()) {
                    out.add(n.asText().strip());
                }
            });
        }
        return out;
    }

    private static String fence(String diff) {
        String d = diff == null ? "" : diff.strip();
        if (d.isEmpty()) {
            return "";
        }
        return "\n```" + (d.length() > MAX_DIFF ? d.substring(0, MAX_DIFF) + "\n…" : d).replace("```", "'''") + "```";
    }

    private static String line(String detail) {
        return detail == null || detail.isBlank() ? "" : "\n" + escape(cut(detail, 600));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
