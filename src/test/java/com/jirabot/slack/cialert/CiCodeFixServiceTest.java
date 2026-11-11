package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.CiCodeFixService.Request;
import com.jirabot.slack.cialert.CiCodeFixService.Result;
import com.jirabot.slack.cialert.CiCodeFixService.Status;
import com.jirabot.slack.cialert.GitHubActionsClient.RunInfo;
import com.jirabot.slack.cialert.GitHubRepoClient.PullInfo;
import com.jirabot.slack.entity.CiAlertEntity;
import com.jirabot.slack.repository.CiAlertRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CiCodeFixServiceTest {

    private static final String RUN_URL = "https://github.com/CryptoLabInc/evi/actions/runs/1000";
    private static final CiAlert ALERT = new CiAlert(CiAlert.Kind.CI, "evi", 1000, RUN_URL, "text");
    private static final RunInfo RUN = new RunInfo(1000, "GPU CI", 77, "abcdef1234", "main", "push", "failure", 1,
            RUN_URL, "msg", "dev", ".github/workflows/gpu.yaml");
    private static final CiFailureAnalyzer.Input INPUT = new CiFailureAnalyzer.Input("evi", "GPU CI", RUN_URL, "main",
            "abcdef1234", "msg", "dev", List.of("a.cpp"), "7번 연속 실패",
            List.of(new CiFailureAnalyzer.FailedJob("gpu", List.of("Build"), "CMake Error: no CMakeLists.txt")), "alert");
    private static final String KEY = "evi/77@main";

    private final ObjectMapper om = new ObjectMapper();
    private GitHubRepoClient repos;
    private CiAlertRepository alerts;
    private FixWorkspace workspace;
    private FixAgentRunner agent;
    private DiffGuard guard;
    private CiCodeFixService service;
    private final FixWorkspace.Workspace ws = new FixWorkspace.Workspace("evi", Path.of("/tmp/x"), Path.of("/tmp/x/repo"),
            Path.of("/tmp/x/agent"), List.of(), List.of());

    @BeforeEach
    void setUp() {
        repos = mock(GitHubRepoClient.class);
        alerts = mock(CiAlertRepository.class);
        workspace = mock(FixWorkspace.class);
        agent = mock(FixAgentRunner.class);
        guard = mock(DiffGuard.class);
        service = service("oauth", true);
        when(workspace.prepare(anyString(), anyString(), anyString())).thenReturn(ws);
        when(repos.listPulls(anyString(), anyString())).thenReturn(List.of());
        when(repos.branchHead("evi", "main")).thenReturn(Optional.of("headsha"));
        when(alerts.findFirstByChannelAndFixKeyAndFixStatusIsNotNullAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                anyString(), anyString(), any(), anyLong())).thenReturn(Optional.empty());
    }

    private CiCodeFixService service(String oauth, boolean enabled) {
        CiFixProperties fixProps = new CiFixProperties(true, oauth, null, 0, 0, 0, null, null, null, null, null, null, 0);
        CiCodeFixProperties codeProps = new CiCodeFixProperties(enabled, 0, null, null, 0, 0, null, 0, 0, 0, 0);
        return new CiCodeFixService(fixProps, codeProps, repos, alerts, workspace, agent, guard, om);
    }

    private static Request req(boolean dryRun, boolean force) {
        return new Request(1, "C", ALERT, RUN, 7, false, "a86dfe287a1234ff", dryRun, force);
    }

    private void agentSays(String json) throws Exception {
        when(agent.runRaw(any(), anyString(), anyString(), any())).thenReturn(new FixAgentRunner.Raw(true, om.readTree(json), ""));
    }

    private static PullInfo pull(int n, String title, String body) {
        return new PullInfo(n, title, body, "https://gh/evi/pull/" + n, "branch-" + n, false, "");
    }

    private static final String FIXED = """
            {"outcome":"fixed","existing_pr":null,"title":"fix(ci): mount the repo source into the GPU container",
             "root_cause":"gpu job 의 docker run 이 소스를 마운트하지 않아 CMakeLists.txt 를 못 찾아요.",
             "summary":"-v 경로를 GITHUB_WORKSPACE 로 바꿨어요.","verification":"actionlint 통과. PR 에서도 도는 워크플로예요.",
             "manual_steps":[],"notes":""}""";

    // ── 언제 도는가 ──────────────────────────────────────────

    @Test
    void wants_onlyFromThreeConsecutiveFailures_orWhenForced() {
        assertThat(service.wants(3, false, false)).isTrue();
        assertThat(service.wants(2, false, false)).as("2번 연속은 아직").isFalse();
        assertThat(service.wants(9, true, false)).as("dry-run 재처리는 요청했을 때만").isFalse();
        assertThat(service.wants(1, true, true)).isTrue();
        assertThat(service("", true).wants(9, false, true)).as("CI 용 토큰이 없으면 꺼진다").isFalse();
        assertThat(service("oauth", false).wants(9, false, true)).isFalse();
    }

    // ── 에이전트 없이 끝나는 경우 ────────────────────────────

    @Test
    void openBotPrForSameWorkflow_isLinkedWithoutAgent() {
        when(repos.listPulls("evi", "open")).thenReturn(List.of(
                pull(4, "other", "<!-- sol-ci-fix key=evi/770@main -->"),
                pull(5, "fix(ci): mount", "body\n<!-- sol-ci-fix key=evi/77@main -->\n")));

        Result r = service.existing(req(false, false)).orElseThrow();

        assertThat(r.status()).isEqualTo(Status.EXISTS);
        assertThat(r.url()).isEqualTo("https://gh/evi/pull/5");
        assertThat(r.label()).isEqualTo("#5 fix(ci): mount");
        assertThat(r.reused()).isFalse();
    }

    @Test
    void attemptWithinCooldown_isReused_unlessForced() {
        CiAlertEntity prior = new CiAlertEntity("C", "1.0");
        prior.recordFix(KEY, "CREATED", "gpu job 이 소스를 마운트하지 않아요.");
        prior.recordPrs("https://gh/evi/pull/12");
        when(alerts.findFirstByChannelAndFixKeyAndFixStatusIsNotNullAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                eq("C"), eq(KEY), any(), eq(1L))).thenReturn(Optional.of(prior));

        Result r = service.existing(req(false, false)).orElseThrow();

        assertThat(r.reused()).isTrue();
        assertThat(r.status()).isEqualTo(Status.CREATED);
        assertThat(r.url()).isEqualTo("https://gh/evi/pull/12");
        assertThat(r.label()).isEqualTo("#12");
        assertThat(service.existing(req(true, true))).as("재처리에서 요청하면 다시 돌린다").isEmpty();
    }

    private void priorAttempt(String status) {
        CiAlertEntity prior = new CiAlertEntity("C", "1.0");
        prior.recordFix(KEY, status, "러너 쪽 문제라 레포 수정으로는 못 고쳐요.");
        when(alerts.findFirstByChannelAndFixKeyAndFixStatusIsNotNullAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                eq("C"), eq(KEY), any(), eq(1L))).thenReturn(Optional.of(prior));
    }

    @Test
    void withinCooldown_aPrTouchingTheFailingWorkflowFile_isLinkedInsteadOfTheOldConclusion() {
        priorAttempt("NOT_FIXABLE");
        when(repos.listPulls("evi", "open")).thenReturn(List.of(
                pull(20, "docs: readme", ""),
                pull(21, "ci: stop mistaking a stale host dir for the mount", "## Why\n...")));
        when(repos.pullFiles("evi", 20)).thenReturn(List.of("README.md"));
        when(repos.pullFiles("evi", 21)).thenReturn(List.of(".github/workflows/gpu.yaml"));

        Result r = service.existing(req(false, false)).orElseThrow();

        assertThat(r.status()).isEqualTo(Status.RELATED);
        assertThat(r.url()).isEqualTo("https://gh/evi/pull/21");
        assertThat(r.label()).startsWith("#21 ci:");
        assertThat(r.reused()).as("새 결과라 기록되고, 다음 알림은 이 PR 을 재사용한다").isFalse();
        verify(agent, never()).runRaw(any(), anyString(), anyString(), any());
    }

    @Test
    void withinCooldown_noRelatedPr_botPr_orPriorAlreadyLinkedAPr_keepsReuse() {
        priorAttempt("NOT_FIXABLE");
        when(repos.listPulls("evi", "open")).thenReturn(List.of(
                pull(20, "docs", ""),
                pull(22, "fix(ci): other", "<!-- sol-ci-fix key=evi/99@main -->")));
        when(repos.pullFiles("evi", 20)).thenReturn(List.of("README.md"));
        when(repos.pullFiles("evi", 22)).thenReturn(List.of(".github/workflows/gpu.yaml"));

        Result r = service.existing(req(false, false)).orElseThrow();
        assertThat(r.status()).as("관련 없는 PR·봇 PR 은 링크하지 않는다").isEqualTo(Status.NOT_FIXABLE);
        assertThat(r.reused()).isTrue();
        verify(repos, never()).pullFiles("evi", 22);

        priorAttempt("RELATED");
        when(repos.pullFiles("evi", 20)).thenReturn(List.of(".github/workflows/gpu.yaml"));
        assertThat(service.existing(req(false, false)).orElseThrow().status())
                .as("이미 PR 을 알린 결과는 다시 찾지 않고 재사용").isEqualTo(Status.RELATED);
    }

    @Test
    void relatedPrLookup_needsTheWorkflowFilePath() {
        priorAttempt("NOT_FIXABLE");
        RunInfo noPath = new RunInfo(1000, "GPU CI", 77, "abcdef1234", "main", "push", "failure", 1, RUN_URL, "msg", "dev", "");
        Request r = new Request(1, "C", ALERT, noPath, 7, false, "fp", false, false);

        when(repos.listPulls("evi", "open")).thenReturn(List.of(pull(21, "ci: x", "")));

        assertThat(service.existing(r).orElseThrow().status()).isEqualTo(Status.NOT_FIXABLE);
        verify(repos, never()).pullFiles(anyString(), anyInt());
    }

    @Test
    void newerRunAlreadyPassed_isSkipped_andNothingElse_meansRunTheAgent() {
        Request recovered = new Request(1, "C", ALERT, RUN, 3, true, "fp", false, false);
        assertThat(service.existing(recovered).orElseThrow().status()).isEqualTo(Status.SKIPPED);
        assertThat(service.existing(req(false, false))).isEmpty();
    }

    // ── 에이전트 결과 ────────────────────────────────────────

    @Test
    void fixed_pushesBranchAndOpensPrAgainstTheFailingBranch() throws Exception {
        agentSays(FIXED);
        when(guard.check(eq(ws), any())).thenReturn(new DiffGuard.Verdict(true, List.of(".github/workflows/gpu.yaml"), 2, "", "-a\n+b"));
        when(repos.branchHead(eq("evi"), eq("bot/ci-fix-gpu-ci-a86dfe28"))).thenReturn(Optional.empty());
        when(repos.createPull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(pull(12, "t", "b"));

        Result r = service.fix(req(false, false), INPUT, "CONFIG", "마운트 경로 문제예요.");

        assertThat(r.status()).isEqualTo(Status.CREATED);
        assertThat(r.url()).isEqualTo("https://gh/evi/pull/12");
        assertThat(r.label()).isEqualTo("#12 fix(ci): mount the repo source into the GPU container");
        verify(workspace).prepare(eq("evi"), anyString(), eq("main"));
        verify(workspace).commitAndPush(eq(ws), eq(List.of(".github/workflows/gpu.yaml")), eq("bot/ci-fix-gpu-ci-a86dfe28"),
                anyString(), eq("sol-ci-bot"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repos).createPull(eq("evi"), eq("bot/ci-fix-gpu-ci-a86dfe28"), eq("main"),
                eq("fix(ci): mount the repo source into the GPU container"), body.capture());
        assertThat(body.getValue()).contains("`GPU CI` 워크플로가 `main` 에서 7번 연속 실패", "실패 job `gpu` → step `Build`",
                "CMakeLists.txt 를 못 찾아요", "이 PR 의 CI 결과로 확인", "<!-- sol-ci-fix key=evi/77@main -->");
        ArgumentCaptor<DiffGuard.Policy> policy = ArgumentCaptor.forClass(DiffGuard.Policy.class);
        verify(guard).check(eq(ws), policy.capture());
        assertThat(policy.getValue().maxLines()).isEqualTo(300);
        assertThat(policy.getValue().maxFiles()).isEqualTo(10);
        verify(workspace).cleanup(ws);
    }

    @Test
    void agentRunsOnOpusHighWithOwnPrompt_andSeesOpenPrsAndFailureData() throws Exception {
        agentSays("{\"outcome\":\"not_fixable\",\"root_cause\":\"r\",\"manual_steps\":[\"s\"]}");
        when(repos.listPulls("evi", "open")).thenReturn(List.of(pull(8, "ci: fix gpu mount", "본문")));
        when(repos.pullFiles("evi", 8)).thenReturn(List.of(".github/workflows/gpu.yaml"));

        service.fix(req(false, false), INPUT, "CONFIG", "마운트 경로 문제예요.");

        ArgumentCaptor<String> task = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<FixAgentRunner.AgentSpec> spec = ArgumentCaptor.forClass(FixAgentRunner.AgentSpec.class);
        verify(agent).runRaw(eq(ws), task.capture(), anyString(), spec.capture());
        assertThat(spec.getValue().model()).isEqualTo("claude-opus-5-5");
        assertThat(spec.getValue().effort()).isEqualTo("high");
        assertThat(spec.getValue().promptFile()).isEqualTo("prompts/ci-fix-agent.md");
        assertThat(spec.getValue().timeoutMinutes()).isEqualTo(30);
        assertThat(spec.getValue().env()).as("SBOM 수정용 GOFLAGS 는 주지 않는다").containsExactly("GOTOOLCHAIN=auto");
        assertThat(task.getValue()).contains("\"workflow_file\":\".github/workflows/gpu.yaml\"", "\"consecutive_failures\":7",
                "\"first_analysis\":{\"category\":\"CONFIG\"", "\"number\":8", "\"files\":[\".github/workflows/gpu.yaml\"]",
                "<data>", "CMake Error: no CMakeLists.txt");
    }

    @Test
    void existingPrNamedByAgent_isLinkedOnlyIfItWasInTheList() throws Exception {
        when(repos.listPulls("evi", "open")).thenReturn(List.of(pull(8, "ci: fix gpu mount", "본문")));
        agentSays("{\"outcome\":\"existing_pr\",\"existing_pr\":8,\"root_cause\":\"마운트 경로\"}");
        Result ok = service.fix(req(false, false), INPUT, "CONFIG", "s");
        assertThat(ok.status()).isEqualTo(Status.EXISTS);
        assertThat(ok.url()).isEqualTo("https://gh/evi/pull/8");
        assertThat(ok.rootCause()).isEqualTo("마운트 경로");

        agentSays("{\"outcome\":\"existing_pr\",\"existing_pr\":999,\"root_cause\":\"x\"}");
        Result bad = service.fix(req(false, false), INPUT, "CONFIG", "s");
        assertThat(bad.status()).isEqualTo(Status.FAILED);
        assertThat(bad.detail()).contains("목록에 없는 #999");
        verify(guard, never()).check(any(), any());
    }

    @Test
    void notFixable_returnsCauseAndManualSteps_withoutTouchingGit() throws Exception {
        agentSays("""
                {"outcome":"not_fixable","root_cause":"DOCS_API_TOKEN 시크릿이 비어 있어요.",
                 "manual_steps":["레포 시크릿 DOCS_API_TOKEN 을 다시 등록","  "],"notes":"토큰 만료로 보여요."}""");

        Result r = service.fix(req(false, false), INPUT, "CONFIG", "s");

        assertThat(r.status()).isEqualTo(Status.NOT_FIXABLE);
        assertThat(r.steps()).containsExactly("레포 시크릿 DOCS_API_TOKEN 을 다시 등록");
        assertThat(r.detail()).isEqualTo("토큰 만료로 보여요.");
        verify(guard, never()).check(any(), any());
        verify(workspace, never()).commitAndPush(any(), anyList(), anyString(), anyString(), anyString());
        verify(workspace).cleanup(ws);
    }

    @Test
    void changeOutsideAllowedScope_isNotPushed() throws Exception {
        agentSays(FIXED);
        when(guard.check(eq(ws), any())).thenReturn(DiffGuard.Verdict.reject("실패를 숨기는 수정은 허용하지 않아요: continue-on-error: true", List.of()));

        Result r = service.fix(req(false, false), INPUT, "CONFIG", "s");

        assertThat(r.status()).isEqualTo(Status.FAILED);
        assertThat(r.detail()).contains("실패를 숨기는 수정");
        assertThat(r.rootCause()).as("원인 분석은 그대로 보여 준다").contains("CMakeLists.txt");
        verify(workspace, never()).commitAndPush(any(), anyList(), anyString(), anyString(), anyString());
        verify(repos, never()).createPull(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void workflowPushRejected_fallsBackToPatchInThread_butOtherPushFailuresPropagate() throws Exception {
        agentSays(FIXED);
        when(guard.check(eq(ws), any())).thenReturn(new DiffGuard.Verdict(true, List.of(".github/workflows/gpu.yaml"), 2, "", "-a\n+b"));
        doThrow(new IllegalStateException("git push 실패: refusing to allow a Personal Access Token to create or update workflow"))
                .when(workspace).commitAndPush(any(), anyList(), anyString(), anyString(), anyString());

        Result r = service.fix(req(false, false), INPUT, "CONFIG", "s");

        assertThat(r.status()).isEqualTo(Status.PATCH);
        assertThat(r.detail()).contains("```-a\n+b```", ".github/workflows/gpu.yaml");
        verify(repos, never()).createPull(anyString(), anyString(), anyString(), anyString(), anyString());

        when(guard.check(eq(ws), any())).thenReturn(new DiffGuard.Verdict(true, List.of("scripts/build.sh"), 2, "", "d"));
        assertThatThrownBy(() -> service.fix(req(false, false), INPUT, "CONFIG", "s")).hasMessageContaining("git push 실패");
    }

    @Test
    void dryRun_returnsDiffWithoutPush() throws Exception {
        agentSays(FIXED);
        when(guard.check(eq(ws), any())).thenReturn(new DiffGuard.Verdict(true, List.of("scripts/build.sh"), 2, "", "-a\n+b"));

        Result r = service.fix(req(true, true), INPUT, "CONFIG", "s");

        assertThat(r.status()).isEqualTo(Status.DRY_RUN);
        assertThat(r.detail()).contains("scripts/build.sh", "```-a\n+b```", "actionlint 통과");
        verify(workspace, never()).commitAndPush(any(), anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void agentFailure_unknownOutcome_botBranchAndMissingBranch() throws Exception {
        when(agent.runRaw(any(), anyString(), anyString(), any())).thenReturn(FixAgentRunner.Raw.failed("에이전트가 30분 안에 끝나지 않았어요"));
        assertThat(service.fix(req(false, false), INPUT, "", "")).satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.FAILED);
            assertThat(r.detail()).contains("30분");
        });
        verify(workspace).cleanup(ws);

        agentSays("{\"outcome\":\"maybe\"}");
        assertThat(service.fix(req(false, false), INPUT, "", "").status()).isEqualTo(Status.FAILED);

        RunInfo botRun = new RunInfo(1, "GPU CI", 77, "s", "bot/ci-fix-x", "push", "failure", 1, RUN_URL, "", "", "");
        assertThat(service.fix(new Request(1, "C", ALERT, botRun, 3, false, "fp", false, false), INPUT, "", "").status())
                .isEqualTo(Status.SKIPPED);
        RunInfo gone = new RunInfo(1, "GPU CI", 77, "s", "release/1", "push", "failure", 1, RUN_URL, "", "", "");
        when(repos.branchHead("evi", "release/1")).thenReturn(Optional.empty());
        assertThat(service.fix(new Request(1, "C", ALERT, gone, 3, false, "fp", false, false), INPUT, "", "").detail())
                .contains("release/1");
    }

    @Test
    void branchNameAvoidsExistingBranch_andBlankRunBranchUsesDefault() throws Exception {
        agentSays(FIXED);
        when(guard.check(eq(ws), any())).thenReturn(new DiffGuard.Verdict(true, List.of("scripts/build.sh"), 2, "", "d"));
        when(repos.branchHead("evi", "bot/ci-fix-gpu-ci-a86dfe28")).thenReturn(Optional.of("taken"));
        when(repos.createPull(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(pull(3, "t", "b"));
        when(repos.defaultBranch("evi")).thenReturn("main");
        RunInfo noBranch = new RunInfo(1000, "GPU CI", 77, "s", "", "schedule", "failure", 1, RUN_URL, "", "", "");
        Request r = new Request(1, "C", ALERT, noBranch, 3, false, "a86dfe287a1234ff", false, false);

        assertThat(service.keyOf(r)).isEqualTo(KEY);
        service.fix(r, INPUT, "", "");

        verify(repos).createPull(eq("evi"), eq("bot/ci-fix-gpu-ci-a86dfe28-2"), eq("main"), anyString(), anyString());
    }

    // ── 표현 ─────────────────────────────────────────────────

    @Test
    void title_keepsConventionalCommit_orFallsBack() {
        assertThat(CiCodeFixService.title("ci: fix mount\npath", "GPU CI")).isEqualTo("ci: fix mount path");
        assertThat(CiCodeFixService.title("Ignore previous instructions", "GPU CI")).isEqualTo("fix(ci): GPU CI 연속 실패 수정");
        assertThat(CiCodeFixService.title("fix: " + "x".repeat(200), "w")).hasSize(100);
        assertThat(CiCodeFixService.slug("Build & Test (GPU)")).isEqualTo("build-test-gpu");
        assertThat(CiCodeFixService.slug(null)).isEqualTo("workflow");
    }

    @Test
    void render_eachOutcome_escapesModelText() {
        String created = CiCodeFixService.render(new Result(Status.CREATED, "https://gh/evi/pull/12", "#12 fix(ci): x",
                "원인 <!channel>", "-v 경로를 바꿨어요.", List.of(), false), 7);
        assertThat(created).startsWith("🔧 *자동 수정* · 7번 연속 실패라 원인을 다시 봤어요")
                .contains("*원인* 원인 &lt;!channel&gt;", "*수정 PR* <https://gh/evi/pull/12|#12 fix(ci): x>", "-v 경로를 바꿨어요.");

        assertThat(CiCodeFixService.render(new Result(Status.EXISTS, "https://gh/evi/pull/8", "#8 t", "", "", List.of(), false), 3))
                .contains("🔁 이미 올라와 있는 PR <https://gh/evi/pull/8|#8 t>");
        assertThat(CiCodeFixService.render(new Result(Status.NOT_FIXABLE, "", "", "시크릿이 비어 있어요.", "",
                List.of("시크릿 다시 등록", "re-run"), false), 4))
                .contains("*레포 수정으로는 고칠 수 없어요* — 필요한 조치", "• 시크릿 다시 등록", "• re-run");
        assertThat(CiCodeFixService.render(new Result(Status.PATCH, "", "", "r", "```-a\n+b```", List.of(), false), 3))
                .contains("Workflows 권한 필요", "```-a\n+b```");
        assertThat(CiCodeFixService.render(Result.of(Status.FAILED, "변경이 너무 커요"), 3)).contains("⚠️ 수정 PR 을 만들지 못했어요: 변경이 너무 커요");
        assertThat(CiCodeFixService.render(Result.of(Status.DRY_RUN, "diff"), 1)).startsWith("🔧 *자동 수정*\n").contains("🧪 dry-run");
        assertThat(CiCodeFixService.render(new Result(Status.RELATED, "https://gh/evi/pull/21", "#21 ci: <x>", "",
                ".github/workflows/gpu.yaml", List.of(), false), 7))
                .contains("이 워크플로 파일(`.github/workflows/gpu.yaml`)을 고치는 PR 이 올라왔어요",
                        "<https://gh/evi/pull/21|#21 ci: &lt;x&gt;>", "이 실패를 고치는지 확인해 주세요");
    }

    @Test
    void render_reusedResult_isOneLine_orSilent() {
        assertThat(CiCodeFixService.render(new Result(Status.CREATED, "https://gh/evi/pull/12", "#12", "", "", List.of(), true), 5))
                .isEqualTo("🔧 수정 PR <https://gh/evi/pull/12|#12> 이 올라와 있어요 — 머지되면 해결돼요");
        assertThat(CiCodeFixService.render(new Result(Status.NOT_FIXABLE, "", "", "", "시크릿이 비어 있어요.", List.of(), true), 5))
                .isEqualTo("🔧 레포 수정으로는 고칠 수 없는 실패예요\n시크릿이 비어 있어요.");
        assertThat(CiCodeFixService.render(new Result(Status.RELATED, "https://gh/evi/pull/21", "#21", "", ".github/workflows/gpu.yaml",
                List.of(), true), 5)).isEqualTo("🔧 이 워크플로 파일을 고치는 열린 PR <https://gh/evi/pull/21|#21> 이 있어요");
        assertThat(CiCodeFixService.render(new Result(Status.FAILED, "", "", "", "x", List.of(), true), 5)).isEmpty();
        assertThat(CiCodeFixService.render(new Result(Status.SKIPPED, "", "", "", "x", List.of(), true), 5)).isEmpty();
    }

    @Test
    void record_keepsFirstLineOfCause() {
        assertThat(CiCodeFixService.record(new Result(Status.CREATED, "u", "l", "첫 줄\n둘째 줄", "d", List.of(), false))).isEqualTo("첫 줄");
        assertThat(CiCodeFixService.record(Result.of(Status.FAILED, "이유"))).isEqualTo("이유");
    }
}
