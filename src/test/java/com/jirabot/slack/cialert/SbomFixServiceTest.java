package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubRepoClient.PullInfo;
import com.jirabot.slack.cialert.GrypeReport.Finding;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SbomFixServiceTest {

    private GitHubRepoClient repos;
    private FixWorkspace workspace;
    private FixAgentRunner agent;
    private DiffGuard guard;
    private FixVerifier verifier;
    private SbomFixService service;
    private final FixWorkspace.Workspace ws = new FixWorkspace.Workspace("HE-KMS", Path.of("/tmp/x"), Path.of("/tmp/x/repo"),
            Path.of("/tmp/x/agent"), List.of(), List.of("github.com/CryptoLabInc/enword/bindings/cgo"));
    private static final CiAlert ALERT = new CiAlert(CiAlert.Kind.SBOM, "envector", 7,
            "https://github.com/CryptoLabInc/envector/actions/runs/7", "text");

    @BeforeEach
    void setUp() {
        repos = mock(GitHubRepoClient.class);
        workspace = mock(FixWorkspace.class);
        agent = mock(FixAgentRunner.class);
        guard = mock(DiffGuard.class);
        verifier = mock(FixVerifier.class);
        CiAlertProperties props = new CiAlertProperties(true, "C", List.of("B"), "CryptoLabInc",
                List.of("he-kms", "envector", "evi", "heracles"), "tok", null, 24, 180, 60, List.of(), null);
        CiFixProperties fixProps = new CiFixProperties(true, "oauth", null, 0, 2, 0, null, null, null, null, null, null, 0);
        service = new SbomFixService(props, fixProps, repos, workspace, agent, guard, verifier, new ObjectMapper());
        when(workspace.prepare(anyString(), anyString())).thenReturn(ws);
        when(repos.defaultBranch(anyString())).thenReturn("main");
        when(repos.branchHead(anyString(), anyString())).thenReturn(Optional.empty());
        when(repos.listPulls(anyString(), anyString())).thenReturn(List.of());
        when(repos.submodulePin(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(verifier.alreadyFixed(any(), anyList())).thenReturn(new FixVerifier.Check(false, List.of(), List.of()));
    }

    private static Finding xnet(String image) {
        return new Finding("GO-2026-6860", "High", "golang.org/x/net", "v0.60.0", "go-module", List.of("0.62.0"), "fixed",
                null, Set.of("CVE-2026-78661"), new TreeSet<>(List.of(image)), new TreeSet<>(List.of("amd64")));
    }

    private static GrypeReport report(Finding... f) {
        return new GrypeReport(List.of(f), 1);
    }

    private static String owner(String img) {
        return img.startsWith("envector-kms") ? "HE-KMS" : "envector";
    }

    private void agentFixes() {
        when(agent.run(any(), anyString(), anyString())).thenReturn(new FixAgentRunner.AgentResult(true, true,
                "fix(deps): bump golang.org/x/net to v0.62.0 for GO-2026-6860", "x/net 을 올렸어요.", "go mod verify 통과", "", ""));
        when(guard.check(ws)).thenReturn(new DiffGuard.Verdict(true, List.of("services/go.mod", "services/go.sum"), 4, "", "diff"));
    }

    @Test
    void success_createsPrWithMarkerAndTable_perOwningRepo() {
        agentFixes();
        when(verifier.verify(eq(ws), anyList())).thenReturn(new FixVerifier.Check(true, List.of(), List.of("grype `services`: 대상 패키지 High/Critical 0건")));
        when(repos.createPull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PullInfo(9, "t", "b", "https://gh/HE-KMS/pull/9", "h", false, ""));

        List<SbomFixService.RepoResult> r = service.fix(1, ALERT, report(xnet("envector-kms-crypto")), SbomFixServiceTest::owner, false);

        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.repo()).isEqualTo("HE-KMS");
            assertThat(x.status()).isEqualTo(SbomFixService.Status.CREATED);
            assertThat(x.url()).isEqualTo("https://gh/HE-KMS/pull/9");
        });
        verify(workspace).commitAndPush(eq(ws), eq(List.of("services/go.mod", "services/go.sum")), eq("bot/sbom-fix-net-0.62.0"), anyString());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repos).createPull(eq("HE-KMS"), eq("bot/sbom-fix-net-0.62.0"), eq("main"),
                eq("fix(deps): bump golang.org/x/net to v0.62.0 for GO-2026-6860"), body.capture());
        assertThat(body.getValue()).contains("| GO-2026-6860 | High | `golang.org/x/net` | v0.60.0 | 0.62.0 | envector-kms-crypto |",
                "grype `services`: 대상 패키지 High/Critical 0건", "<!-- sol-sbom-fix vulns=GO-2026-6860 -->");
        verify(workspace).cleanup(ws);
    }

    @Test
    void openPrWithSameVuln_isReused_withoutRunningTheAgent() {
        when(repos.listPulls("envector", "open")).thenReturn(List.of(
                new PullInfo(5, "fix(deps): bump x/net", "GO-2026-6860 해결", "https://gh/envector/pull/5", "h", false, "")));

        List<SbomFixService.RepoResult> r = service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false);

        assertThat(r.get(0).status()).isEqualTo(SbomFixService.Status.EXISTS);
        assertThat(r.get(0).url()).isEqualTo("https://gh/envector/pull/5");
        verify(agent, never()).run(any(), anyString(), anyString());
    }

    @Test
    void remainingAfterFirstAttempt_retriesWithFeedback_thenSucceeds() {
        agentFixes();
        when(verifier.verify(eq(ws), anyList()))
                .thenReturn(new FixVerifier.Check(false, List.of("GHSA-1 google.golang.org/grpc v1.83.1 → 1.83.2 (services)"), List.of()))
                .thenReturn(new FixVerifier.Check(true, List.of(), List.of("ok")));
        when(repos.createPull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PullInfo(3, "t", "b", "u", "h", false, ""));

        service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false);

        ArgumentCaptor<String> task = ArgumentCaptor.forClass(String.class);
        verify(agent, times(2)).run(eq(ws), task.capture(), anyString());
        assertThat(task.getAllValues().get(0)).doesNotContain("retry_feedback").contains("stub_modules");
        assertThat(task.getAllValues().get(1)).contains("retry_feedback", "1.83.2");
    }

    @Test
    void stillVulnerableAfterMaxAttempts_failsWithoutPush() {
        agentFixes();
        when(verifier.verify(eq(ws), anyList())).thenReturn(new FixVerifier.Check(false, List.of("GO-2026-6860 left"), List.of()));

        List<SbomFixService.RepoResult> r = service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false);

        assertThat(r.get(0).status()).isEqualTo(SbomFixService.Status.FAILED);
        assertThat(r.get(0).detail()).contains("다시 검사해도 남았어요", "GO-2026-6860 left");
        verify(workspace, never()).commitAndPush(any(), anyList(), anyString(), anyString());
        verify(workspace).cleanup(ws);
    }

    @Test
    void agentErrorOrGuardRejection_failsWithReason() {
        when(agent.run(any(), anyString(), anyString())).thenReturn(FixAgentRunner.AgentResult.failed("에이전트 실행 오류"));
        assertThat(service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false).get(0).detail())
                .isEqualTo("에이전트 실행 오류");

        when(agent.run(any(), anyString(), anyString())).thenReturn(new FixAgentRunner.AgentResult(true, true, "t", "s", "", "", ""));
        when(guard.check(ws)).thenReturn(DiffGuard.Verdict.reject("허용되지 않은 파일을 바꿨어요: main.go", List.of()));
        assertThat(service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false).get(0).detail())
                .isEqualTo("허용되지 않은 파일을 바꿨어요: main.go");
        verify(workspace, never()).commitAndPush(any(), anyList(), anyString(), anyString());
    }

    @Test
    void dryRun_reportsDiffWithoutPushOrPr() {
        agentFixes();
        when(verifier.verify(eq(ws), anyList())).thenReturn(new FixVerifier.Check(true, List.of(), List.of("ok")));

        SbomFixService.RepoResult r = service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, true).get(0);

        assertThat(r.status()).isEqualTo(SbomFixService.Status.DRY_RUN);
        assertThat(r.detail()).contains("services/go.mod", "x/net 을 올렸어요.", "diff");
        verify(workspace, never()).commitAndPush(any(), anyList(), anyString(), anyString());
        verify(repos, never()).createPull(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ── 이미 반영됨 / 에이전트 "변경 없음" (v0.0.98) ──

    @Test
    void alreadyFixedOnTheBaseBranch_skipsWithoutRunningTheAgent() {
        when(verifier.alreadyFixed(eq(ws), anyList())).thenReturn(
                new FixVerifier.Check(true, List.of(), List.of("grype `services`: 대상 패키지 High/Critical 0건")));

        SbomFixService.RepoResult r = service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false).get(0);

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SKIPPED);
        assertThat(r.detail()).contains("이미 반영돼 있어요", "High/Critical 0건");
        verify(agent, never()).run(any(), anyString(), anyString());
        verify(workspace).cleanup(ws);
    }

    @Test
    void agentReportsNoChange_neverOpensAPr_evenIfFilesChanged() {
        when(agent.run(any(), anyString(), anyString())).thenReturn(new FixAgentRunner.AgentResult(true, false,
                "", "이미 v0.62.0 이에요", "", "변경 사항 없음", ""));
        when(guard.check(ws)).thenReturn(new DiffGuard.Verdict(true, List.of("services/go.sum"), 0, "", ""));

        SbomFixService.RepoResult r = service.fix(1, ALERT, report(xnet("envector-backend")), SbomFixServiceTest::owner, false).get(0);

        assertThat(r.status()).isEqualTo(SbomFixService.Status.FAILED);
        assertThat(r.detail()).isEqualTo("에이전트가 고치지 못했어요 (변경 사항 없음)");
        verify(guard, never()).check(any());
        verify(repos, never()).createPull(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ── 서브모듈 (v0.0.97): 봇은 만들지 않고 팀의 업데이트 PR 을 찾아 알린다 ──

    private static final String BASE_PIN = "1".repeat(40);
    private static final String FIX_SHA = "f".repeat(40);
    private static final String TEAM_BRANCH = "update_he_kms_commit_main";

    // HE-KMS 에서 GO-2026-6860 을 고친 PR 이 머지됐고, envector main 의 external/HE-KMS 는 아직 그 전 커밋이다.
    private void ownerFixMerged(String fixPrBody) {
        when(repos.submodulePin("envector", "external/HE-KMS", "main")).thenReturn(Optional.of(BASE_PIN));
        when(repos.listPulls("HE-KMS", "closed")).thenReturn(List.of(
                new PullInfo(9, "fix(deps): bump x/net", fixPrBody, "https://gh/HE-KMS/pull/9", "h", true, FIX_SHA)));
    }

    private void openPull(int number, String headRef, String pin, boolean hasFix) {
        when(repos.submodulePin("envector", "external/HE-KMS", headRef)).thenReturn(Optional.of(pin));
        when(repos.contains("HE-KMS", FIX_SHA, pin)).thenReturn(hasFix);
    }

    private SbomFixService.RepoResult fixKms() {
        return service.fix(1, ALERT, report(xnet("envector-kms-crypto")), SbomFixServiceTest::owner, false).get(0);
    }

    @Test
    void teamSubmodulePrWithTheFix_isLinked_andNothingIsCreated() {
        ownerFixMerged("...\n<!-- sol-sbom-fix vulns=GO-2026-6860 -->\n");
        when(repos.listPulls("envector", "open")).thenReturn(List.of(
                new PullInfo(2770, "pin evi and HE-KMS", "", "https://gh/envector/pull/2770", "feature", false, ""),
                new PullInfo(2744, "[automated] Update HE-KMS Commit", "", "https://gh/envector/pull/2744", TEAM_BRANCH, false, "")));
        openPull(2744, TEAM_BRANCH, "2".repeat(40), true);
        openPull(2770, "feature", "3".repeat(40), true);

        SbomFixService.RepoResult r = fixKms();

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SUBMODULE_PR);
        assertThat(r.repo()).isEqualTo("envector");
        assertThat(r.url()).as("팀 PR 이 먼저").isEqualTo("https://gh/envector/pull/2744");
        assertThat(r.note()).isEqualTo("HE-KMS #9");
        verify(repos, never()).createPull(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(agent, never()).run(any(), anyString(), anyString());
    }

    @Test
    void teamPrStillOnAnOldPin_fallsBackToAnotherPrThatHasTheFix() {
        ownerFixMerged("GO-2026-6860 을 고쳤어요");   // 사람이 만든 수정 PR (봇 표식 없음)
        when(repos.listPulls("envector", "open")).thenReturn(List.of(
                new PullInfo(2744, "[automated] Update HE-KMS Commit", "", "https://gh/envector/pull/2744", TEAM_BRANCH, false, ""),
                new PullInfo(2770, "pin evi and HE-KMS", "", "https://gh/envector/pull/2770", "feature", false, ""),
                new PullInfo(2800, "docs", "", "https://gh/envector/pull/2800", "docs", false, "")));
        openPull(2744, TEAM_BRANCH, "2".repeat(40), false);
        openPull(2770, "feature", "3".repeat(40), true);
        openPull(2800, "docs", BASE_PIN, false);   // 서브모듈을 안 건드리는 PR

        SbomFixService.RepoResult r = fixKms();

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SUBMODULE_PR);
        assertThat(r.url()).isEqualTo("https://gh/envector/pull/2770");
    }

    @Test
    void noPrCarriesTheFix_onlyReportsIt_withTheWorkflowLink() {
        ownerFixMerged("...\n<!-- sol-sbom-fix vulns=GO-2026-6860 -->\n");
        when(repos.listPulls("envector", "open")).thenReturn(List.of(
                new PullInfo(2744, "[automated] Update HE-KMS Commit", "", "https://gh/envector/pull/2744", TEAM_BRANCH, false, "")));
        openPull(2744, TEAM_BRANCH, "2".repeat(40), false);

        SbomFixService.RepoResult r = fixKms();

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SUBMODULE_MISSING);
        assertThat(r.url()).isEqualTo("https://github.com/CryptoLabInc/envector/actions/workflows/update_submodule.yaml");
        assertThat(r.detail()).contains("HE-KMS #9", "external/HE-KMS", "#2744 은 아직 수정 전 커밋 2222222");
        verify(repos, never()).createPull(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(agent, never()).run(any(), anyString(), anyString());
    }

    @Test
    void basePinAlreadyHasTheFix_isSkipped() {
        ownerFixMerged("...\n<!-- sol-sbom-fix vulns=GO-2026-6860 -->\n");
        when(repos.contains("HE-KMS", FIX_SHA, BASE_PIN)).thenReturn(true);

        SbomFixService.RepoResult r = fixKms();

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SKIPPED);
        assertThat(r.detail()).contains("이미 HE-KMS #9 수정이 들어 있어요");
        verify(repos, never()).listPulls("envector", "open");
    }

    @Test
    void ownerNotFixedYet_goesToTheAgentInTheOwnerRepo() {
        when(repos.submodulePin("envector", "external/HE-KMS", "main")).thenReturn(Optional.of(BASE_PIN));
        when(repos.listPulls("HE-KMS", "closed")).thenReturn(List.of(
                new PullInfo(8, "other", "GO-2026-1111", "https://gh/HE-KMS/pull/8", "h", true, FIX_SHA)));
        agentFixes();
        when(verifier.verify(any(), anyList())).thenReturn(new FixVerifier.Check(true, List.of(), List.of("grype ok")));
        when(repos.createPull(eq("HE-KMS"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PullInfo(10, "t", "b", "https://gh/HE-KMS/pull/10", "h", false, ""));

        SbomFixService.RepoResult r = fixKms();

        assertThat(r.status()).isEqualTo(SbomFixService.Status.CREATED);
        assertThat(r.repo()).isEqualTo("HE-KMS");
        verify(repos, never()).contains(anyString(), anyString(), anyString());
    }

    @Test
    void ownerOutsideAllowList_isSkipped() {
        SbomFixService.RepoResult r = service.fix(1, ALERT, report(xnet("envector-backend")), img -> "other-repo", false).get(0);

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SKIPPED);
    }

    @Test
    void render_titleAndSlug() {
        String text = SbomFixService.render(List.of(
                new SbomFixService.RepoResult("HE-KMS", SbomFixService.Status.CREATED, "https://gh/1", "#1 fix"),
                new SbomFixService.RepoResult("envector", SbomFixService.Status.EXISTS, "https://gh/2", "#2"),
                new SbomFixService.RepoResult("heracles", SbomFixService.Status.FAILED, "", "a <b>"),
                new SbomFixService.RepoResult("envector", SbomFixService.Status.SUBMODULE_PR, "https://gh/2744",
                        "#2744 [automated] Update HE-KMS Commit", "HE-KMS #430"),
                new SbomFixService.RepoResult("envector", SbomFixService.Status.SUBMODULE_MISSING, "https://gh/wf", "PR 이 없어요")));

        assertThat(text).isEqualTo("🔧 *자동 수정 PR*\n• *HE-KMS* — <https://gh/1|#1 fix>\n"
                + "• *envector* — 🔁 이미 진행 중인 PR <https://gh/2|#2>\n• *heracles* — ⚠️ 실패: a &lt;b&gt;\n"
                + "• *envector* — 🔁 서브모듈 업데이트 PR <https://gh/2744|#2744 [automated] Update HE-KMS Commit> 에 HE-KMS #430 수정이 들어 있어요 — 머지되면 해결돼요\n"
                + "• *envector* — ⏳ PR 이 없어요 — <https://gh/wf|update_submodule 실행> 이 필요해요");
        assertThat(SbomFixService.title("", List.of(xnet("a")))).isEqualTo("fix(deps): bump golang.org/x/net to 0.62.0 for GO-2026-6860");
        assertThat(SbomFixService.title("fix: " + "x".repeat(200), List.of(xnet("a")))).hasSize(100);
        assertThat(SbomFixService.slug("google.golang.org/grpc")).isEqualTo("grpc");
    }
}
