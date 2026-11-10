package com.jirabot.slack.cialert;

import static com.jirabot.slack.cialert.CiReplyRenderer.escape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubRepoClient.PullInfo;
import com.jirabot.slack.cialert.GrypeReport.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
    // 팀의 서브모듈 업데이트 워크플로와 그 PR 브랜치 (envector .github/workflows/update_submodule.yaml)
    static final String TEAM_SUBMODULE_WORKFLOW = "update_submodule.yaml";
    static final String TEAM_SUBMODULE_BRANCH = "update_%s_commit_%s";

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

    // SUBMODULE_PR: 수정이 들어간 서브모듈 업데이트 PR 을 찾음, SUBMODULE_MISSING: 그런 PR 이 없음 (봇은 만들지 않는다)
    public enum Status { CREATED, EXISTS, SUBMODULE_PR, SUBMODULE_MISSING, DRY_RUN, FAILED, SKIPPED }

    // note: 링크 뒤에 붙는 설명 (SUBMODULE_PR 이면 그 PR 에 들어간 소유 레포 수정)
    public record RepoResult(String repo, Status status, String url, String detail, String note) {
        public RepoResult(String repo, Status status, String url, String detail) {
            this(repo, status, url, detail, "");
        }
    }

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

        // 2. 다른 레포(서브모듈) 소유인데 그 레포에서 이미 고쳐졌다 → 알림 레포의 서브모듈만 올라가면 된다.
        //    서브모듈은 팀의 업데이트 PR 로 올리므로, 그 PR 을 찾아 알리기만 한다 (v0.0.97).
        if (!repo.equalsIgnoreCase(alert.repo())) {
            Optional<RepoResult> sub = submoduleStatus(alert.repo(), repo, ids(targets));
            if (sub.isPresent()) {
                return sub.get();
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

    // STUDY: 서브모듈 핀은 팀 워크플로(envector update_submodule.yaml)가 올린다 — 소유 레포 main 에 push 가 있을 때마다
    //        고정 브랜치(update_<component>_commit_<base>)의 PR 을 만들거나 갱신하고, proto 코드도 다시 생성한다.
    //        봇이 gitlink 만 바꾼 PR 을 따로 만들면 그 PR 과 겹친다(envector #2796 ↔ #2744). 그래서 봇은
    //        수정 커밋이 들어간 핀을 가진 열린 PR(팀 PR 먼저, 사람 PR 도)을 찾아 링크하고, 없으면 알리기만 한다.
    private Optional<RepoResult> submoduleStatus(String alertRepo, String owner, Set<String> ids) {
        String path = "external/" + owner;
        String base = repos.defaultBranch(alertRepo);
        Optional<String> basePin = repos.submodulePin(alertRepo, path, base);
        if (basePin.isEmpty()) {
            return Optional.empty();   // 서브모듈이 아니면 소유 레포를 직접 고친다
        }
        // 소유 레포에서 이 취약점을 고친 머지된 PR (봇 표식이든 사람이 쓴 ID 든)
        Optional<PullInfo> fix = repos.listPulls(owner, "closed").stream()
                .filter(PullInfo::merged).filter(p -> !p.mergeCommitSha().isBlank())
                .filter(p -> ids.stream().anyMatch(p.text()::contains)).findFirst();
        if (fix.isEmpty()) {
            return Optional.empty();   // 아직 소유 레포에서 안 고쳐졌다 → 에이전트가 소유 레포를 고친다
        }
        String fixSha = fix.get().mergeCommitSha();
        String fixRef = owner + " #" + fix.get().number();
        if (repos.contains(owner, fixSha, basePin.get())) {
            return Optional.of(new RepoResult(alertRepo, Status.SKIPPED, "",
                    base + " 의 " + path + " 에 이미 " + fixRef + " 수정이 들어 있어요 (이 알림은 그 전 커밋의 빌드)"));
        }
        String teamBranch = TEAM_SUBMODULE_BRANCH.formatted(owner.toLowerCase(Locale.ROOT).replace('-', '_'), base);
        List<PullInfo> open = new ArrayList<>(repos.listPulls(alertRepo, "open"));
        open.sort(Comparator.comparing(p -> !teamBranch.equals(p.headRef())));   // 팀 PR 먼저
        PullInfo stale = null;
        String stalePin = "";
        for (PullInfo p : open) {
            Optional<String> pin = repos.submodulePin(alertRepo, path, p.headRef());
            if (pin.isEmpty() || pin.get().equals(basePin.get())) {
                continue;   // 이 PR 은 서브모듈을 건드리지 않는다
            }
            if (repos.contains(owner, fixSha, pin.get())) {
                return Optional.of(new RepoResult(alertRepo, Status.SUBMODULE_PR, p.htmlUrl(),
                        "#" + p.number() + " " + p.title(), fixRef));
            }
            if (stale == null) {
                stale = p;
                stalePin = pin.get();
            }
        }
        String why = fixRef + " 수정은 머지됐지만 그 커밋을 담은 " + path + " 업데이트 PR 이 없어요"
                + (stale == null ? "" : " (#" + stale.number() + " 은 아직 수정 전 커밋 " + stalePin.substring(0, 7) + ")");
        return Optional.of(new RepoResult(alertRepo, Status.SUBMODULE_MISSING,
                "https://github.com/" + props.org() + "/" + alertRepo + "/actions/workflows/" + TEAM_SUBMODULE_WORKFLOW, why));
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
        sb.append("#sol-dev-alarm SBOM 알림([run](").append(alert.runUrl()).append("))의 Grype 검사에서 이 레포가 고칠 High/Critical 취약점이 나왔습니다.\n\n");
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
        sb.append("\n---\n🤖 #sol-dev-alarm 알림 봇이 자동으로 만든 PR 입니다 (Claude 에이전트가 수정, 봇이 변경 범위·grype 재검사로 확인). 자동 머지는 하지 않습니다.\n");
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
                case SUBMODULE_PR -> sb.append("🔁 서브모듈 업데이트 PR <").append(r.url()).append("|").append(escape(r.detail()))
                        .append("> 에 ").append(escape(r.note())).append(" 수정이 들어 있어요 — 머지되면 해결돼요");
                case SUBMODULE_MISSING -> sb.append("⏳ ").append(escape(r.detail())).append(" — <").append(r.url())
                        .append("|update_submodule 실행> 이 필요해요");
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
