package com.jirabot.slack.cialert;

import static com.jirabot.slack.cialert.CiReplyRenderer.escape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubRepoClient.PullInfo;
import com.jirabot.slack.cialert.GrypeReport.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: SBOM 취약점 자동 수정 PR (v0.0.94). 표 답글 뒤에 고칠 레포마다:
//        중복 확인 → 작업 사본 → Claude 에이전트(컨테이너) → DiffGuard → grype 재검사(남으면 1회 재시도) → 커밋·push·PR.
//        HE-KMS 소유 취약점은 HE-KMS 수정 PR 이 머지된 뒤에도 envector 알림에 남으면, envector 의 HE-KMS 서브모듈만
//        올리는 PR 을 에이전트 없이(Git Data API) 만든다. 자동 머지는 없다.
@Service
public class SbomFixService {

    private static final Logger log = LoggerFactory.getLogger(SbomFixService.class);
    static final String MARKER = "sol-sbom-fix";
    private static final Pattern MARKER_IDS = Pattern.compile("<!-- " + MARKER + " vulns=([^ ]+) -->");

    private final CiAlertProperties props;
    private final CiFixProperties fixProps;
    private final GitHubRepoClient repos;
    private final FixWorkspace workspace;
    private final FixAgentRunner agent;
    private final DiffGuard guard;
    private final FixVerifier verifier;
    private final ObjectMapper objectMapper;

    public SbomFixService(CiAlertProperties props, CiFixProperties fixProps, GitHubRepoClient repos, FixWorkspace workspace,
                          FixAgentRunner agent, DiffGuard guard, FixVerifier verifier, ObjectMapper objectMapper) {
        this.props = props;
        this.fixProps = fixProps;
        this.repos = repos;
        this.workspace = workspace;
        this.agent = agent;
        this.guard = guard;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    public enum Status { CREATED, EXISTS, SUBMODULE_PR, DRY_RUN, FAILED, SKIPPED }

    public record RepoResult(String repo, Status status, String url, String detail) {}

    public boolean enabled() {
        return fixProps.effectivelyEnabled();
    }

    public List<RepoResult> fix(long alertId, CiAlert alert, GrypeReport report, Function<String, String> ownerOf,
                                boolean dryRun) {
        Map<String, List<Finding>> byOwner = new LinkedHashMap<>();
        SbomReplyRenderer.byOwner(report.findings().stream().filter(Finding::fixable).toList(), ownerOf)
                .forEach(byOwner::put);
        List<RepoResult> results = new ArrayList<>();
        for (Map.Entry<String, List<Finding>> e : byOwner.entrySet()) {
            String repo = e.getKey();
            if (props.repos().stream().noneMatch(r -> r.equalsIgnoreCase(repo))) {
                results.add(new RepoResult(repo, Status.SKIPPED, "", "자동 수정 대상 레포가 아니에요"));
                continue;
            }
            try {
                results.add(fixRepo(alertId, alert, repo, e.getValue(), dryRun));
            } catch (RuntimeException ex) {
                log.warn("sbom fix failed repo={}: {}", repo, ex.toString());
                results.add(new RepoResult(repo, Status.FAILED, "", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        return results;
    }

    private RepoResult fixRepo(long alertId, CiAlert alert, String repo, List<Finding> findings, boolean dryRun) {
        Set<String> ids = ids(findings);
        // 1. 같은 취약점 ID 가 들어간 열린 PR (사람이 만든 것 포함)
        List<PullInfo> open = repos.listPulls(repo, "open");
        Map<String, PullInfo> covered = new LinkedHashMap<>();
        for (String id : ids) {
            open.stream().filter(p -> p.text().contains(id)).findFirst().ifPresent(p -> covered.put(id, p));
        }
        if (covered.keySet().containsAll(ids)) {
            PullInfo p = covered.values().iterator().next();
            return new RepoResult(repo, Status.EXISTS, p.htmlUrl(), "#" + p.number());
        }
        List<Finding> targets = findings.stream().filter(f -> !covered.containsKey(f.vulnId())).toList();

        // 2. 다른 레포(서브모듈) 소유인데 그 레포의 봇 수정 PR 이 이미 머지됐다 → 알림 레포의 서브모듈만 올린다
        if (!repo.equalsIgnoreCase(alert.repo())) {
            Optional<RepoResult> bump = submoduleBump(alert.repo(), repo, ids(targets), dryRun);
            if (bump.isPresent()) {
                return bump.get();
            }
        }

        // 3. 에이전트 수정
        String jobId = alertId + "-" + repo.toLowerCase() + "-" + (System.currentTimeMillis() % 100000);
        agent.ensureImage();
        FixWorkspace.Workspace ws = workspace.prepare(repo, jobId);
        try {
            List<String> feedback = List.of();
            FixAgentRunner.AgentResult result = null;
            DiffGuard.Verdict verdict = null;
            FixVerifier.Check check = null;
            for (int attempt = 1; attempt <= fixProps.maxAttempts(); attempt++) {
                result = agent.run(ws, task(repo, targets, feedback, ws), jobId + "-" + attempt);
                if (!result.ok()) {
                    return new RepoResult(repo, Status.FAILED, "", result.error());
                }
                verdict = guard.check(ws);
                if (!verdict.ok()) {
                    String why = result.changed() ? verdict.reason() : "에이전트가 고치지 못했어요" + note(result.notes());
                    return new RepoResult(repo, Status.FAILED, "", why);
                }
                check = verifier.verify(ws, targets);
                if (check.ok()) {
                    break;
                }
                feedback = check.remaining();
                log.info("sbom fix attempt {} left {} findings repo={}", attempt, feedback.size(), repo);
            }
            if (check == null || !check.ok()) {
                return new RepoResult(repo, Status.FAILED, "", "다시 검사해도 남았어요: " + String.join(", ", feedback));
            }
            String title = title(result.title(), targets);
            if (dryRun) {
                return new RepoResult(repo, Status.DRY_RUN, "", dryRunDetail(verdict, check, result));
            }
            String base = repos.defaultBranch(repo);
            String branch = freeBranch(repo, "bot/sbom-fix-" + slug(targets.get(0).pkg()) + "-" + slug(targets.get(0).preferredFix()));
            workspace.commitAndPush(ws, verdict.files(), branch, title + "\n\n" + String.join(", ", ids(targets)));
            PullInfo pr = repos.createPull(repo, branch, base, title, body(alert, repo, targets, verdict, check, result));
            log.info("sbom fix PR created repo={} url={}", repo, pr.htmlUrl());
            return new RepoResult(repo, Status.CREATED, pr.htmlUrl(), "#" + pr.number() + " " + title);
        } finally {
            workspace.cleanup(ws);
        }
    }

    private Optional<RepoResult> submoduleBump(String alertRepo, String owner, Set<String> ids, boolean dryRun) {
        String path = "external/" + owner;
        String alertBase = repos.defaultBranch(alertRepo);
        Optional<String> pin = repos.submodulePin(alertRepo, path, alertBase);
        if (pin.isEmpty()) {
            return Optional.empty();
        }
        Optional<PullInfo> merged = repos.listPulls(owner, "closed").stream()
                .filter(PullInfo::merged).filter(p -> markerIds(p.body()).stream().anyMatch(ids::contains)).findFirst();
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        String head = repos.branchHead(owner, repos.defaultBranch(owner)).orElse("");
        if (head.isEmpty() || head.equals(pin.get())) {
            return Optional.empty();
        }
        String title = "fix(deps): pin " + owner + " at " + head.substring(0, 7) + " for " + String.join(", ", ids);
        if (dryRun) {
            return Optional.of(new RepoResult(alertRepo, Status.DRY_RUN, "",
                    path + " " + pin.get().substring(0, 7) + " → " + head.substring(0, 7) + " (" + owner + " " + merged.get().htmlUrl() + " 반영)"));
        }
        String branch = freeBranch(alertRepo, "bot/sbom-pin-" + slug(owner) + "-" + head.substring(0, 7));
        repos.commitGitlink(alertRepo, alertBase, path, head, title, branch);
        String body = "## 문제\n" + String.join(", ", ids) + " 는 " + owner + " 에서 고쳐졌지만(" + merged.get().htmlUrl()
                + "), 이 레포의 `" + path + "` 서브모듈이 아직 이전 커밋(`" + pin.get().substring(0, 7) + "`)을 가리켜 이미지에 남아 있습니다.\n\n"
                + "## 채택한 해결 방안\n`" + path + "` 를 " + owner + " main(`" + head.substring(0, 7) + "`)으로 올립니다.\n\n"
                + "## 검증 방법\n이 PR 의 CI 와 머지 후 SBOM 검사가 확인합니다.\n\n"
                + "---\n🤖 #sol-sbom-ci 알림 봇이 자동으로 만든 PR 입니다. 자동 머지는 하지 않습니다.\n"
                + "<!-- " + MARKER + " vulns=" + String.join(",", ids) + " -->\n";
        PullInfo pr = repos.createPull(alertRepo, branch, alertBase, title, body);
        return Optional.of(new RepoResult(alertRepo, Status.SUBMODULE_PR, pr.htmlUrl(), "#" + pr.number() + " " + title));
    }

    String task(String repo, List<Finding> targets, List<String> feedback, FixWorkspace.Workspace ws) {
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Finding f : targets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.vulnId());
            m.put("severity", f.severity());
            m.put("package", f.pkg());
            m.put("type", f.type());
            m.put("installed", f.version());
            m.put("fix_versions", f.fixVersions());
            m.put("suggested", f.preferredFix());
            m.put("images", f.images());
            fs.add(m);
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("repo", repo);
        task.put("findings", fs);
        if (!ws.stubbed().isEmpty()) {
            task.put("stub_modules", ws.stubbed());
        }
        if (!feedback.isEmpty()) {
            task.put("retry_feedback", feedback);
        }
        try {
            return "Fix these Grype findings. Task:\n" + objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String title(String agentTitle, List<Finding> targets) {
        String t = agentTitle == null ? "" : agentTitle.strip().replaceAll("[\\r\\n]+", " ");
        if (t.isEmpty() || !t.startsWith("fix")) {
            Finding f = targets.get(0);
            t = "fix(deps): bump " + f.pkg() + " to " + f.preferredFix() + " for " + String.join(", ", ids(targets));
        }
        return t.length() > 100 ? t.substring(0, 99) + "…" : t;
    }

    static String body(CiAlert alert, String repo, List<Finding> targets, DiffGuard.Verdict verdict, FixVerifier.Check check,
                       FixAgentRunner.AgentResult result) {
        StringBuilder sb = new StringBuilder("## 문제\n");
        sb.append("#sol-sbom-ci SBOM 알림([run](").append(alert.runUrl()).append("))의 Grype 검사에서 이 레포가 고칠 High/Critical 취약점이 나왔습니다.\n\n");
        sb.append("| 취약점 | 심각도 | 패키지 | 현재 | 고칠 버전 | 이미지 |\n|---|---|---|---|---|---|\n");
        for (Finding f : targets) {
            sb.append("| ").append(f.vulnId()).append(" | ").append(f.severity()).append(" | `").append(f.pkg()).append("` | ")
                    .append(f.version()).append(" | ").append(f.preferredFix()).append(" | ")
                    .append(String.join(", ", f.images())).append(" |\n");
        }
        sb.append("\n## 채택한 해결 방안\n").append(result.summary().isBlank() ? "의존성 버전을 올렸습니다." : result.summary()).append('\n');
        sb.append("\n변경 파일: ").append(String.join(", ", verdict.files().stream().map(f -> "`" + f + "`").toList()))
                .append(" (go.sum 제외 ").append(verdict.changedLines()).append("줄)\n");
        sb.append("\n## 검증 방법\n");
        check.lines().forEach(l -> sb.append("- ").append(l).append('\n'));
        if (!result.verification().isBlank()) {
            sb.append("- 에이전트: ").append(result.verification()).append('\n');
        }
        sb.append("- 빌드와 테스트는 이 PR 의 CI 가 확인합니다.\n");
        if (!result.notes().isBlank()) {
            sb.append("\n## 참고\n").append(result.notes()).append('\n');
        }
        sb.append("\n---\n🤖 #sol-sbom-ci 알림 봇이 자동으로 만든 PR 입니다 (Claude 에이전트가 수정, 봇이 변경 범위·grype 재검사로 확인). 자동 머지는 하지 않습니다.\n");
        sb.append("<!-- ").append(MARKER).append(" vulns=").append(String.join(",", ids(targets))).append(" -->\n");
        return sb.toString();
    }

    private static String dryRunDetail(DiffGuard.Verdict verdict, FixVerifier.Check check, FixAgentRunner.AgentResult result) {
        String diff = verdict.diff().length() > 2500 ? verdict.diff().substring(0, 2500) + "\n…" : verdict.diff();
        return "파일 " + verdict.files() + " (" + verdict.changedLines() + "줄)\n" + String.join("\n", check.lines())
                + "\n요약: " + result.summary() + (result.notes().isBlank() ? "" : "\n참고: " + result.notes())
                + "\n```" + diff.replace("```", "'''") + "```";
    }

    // 결과 답글 (스레드). 링크는 Slack 형식, 나머지는 이스케이프.
    public static String render(List<RepoResult> results) {
        StringBuilder sb = new StringBuilder("🔧 *자동 수정 PR*\n");
        for (RepoResult r : results) {
            sb.append("• *").append(escape(r.repo())).append("* — ");
            switch (r.status()) {
                case CREATED -> sb.append("<").append(r.url()).append("|").append(escape(r.detail())).append(">");
                case SUBMODULE_PR -> sb.append("서브모듈 갱신 <").append(r.url()).append("|").append(escape(r.detail())).append(">");
                case EXISTS -> sb.append("🔁 이미 진행 중인 PR <").append(r.url()).append("|").append(escape(r.detail())).append(">");
                case DRY_RUN -> sb.append("🧪 dry-run (push 안 함)\n").append(escape(r.detail()));
                case SKIPPED -> sb.append("건너뜀: ").append(escape(r.detail()));
                case FAILED -> sb.append("⚠️ 실패: ").append(escape(r.detail()));
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    static Set<String> ids(List<Finding> fs) {
        Set<String> out = new LinkedHashSet<>();
        fs.forEach(f -> out.add(f.vulnId()));
        return out;
    }

    static List<String> markerIds(String body) {
        Matcher m = MARKER_IDS.matcher(body == null ? "" : body);
        return m.find() ? List.of(m.group(1).split(",")) : List.of();
    }

    private String freeBranch(String repo, String base) {
        String b = base;
        for (int i = 2; repos.branchHead(repo, b).isPresent(); i++) {
            b = base + "-" + i;
        }
        return b;
    }

    static String slug(String s) {
        String t = s == null ? "" : s.toLowerCase().replaceAll("^.*/", "").replaceAll("[^a-z0-9.]+", "-");
        return t.isEmpty() ? "x" : t;
    }

    private static String note(String notes) {
        return notes == null || notes.isBlank() ? "" : " (" + notes + ")";
    }
}
