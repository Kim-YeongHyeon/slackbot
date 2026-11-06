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
                List.of("he-kms", "envector", "evi", "heracles"), "tok", null, 24, 180, 60, List.of());
        CiFixProperties fixProps = new CiFixProperties(true, "oauth", null, 0, 2, 0, null, null, null, null, null, null, 0);
        service = new SbomFixService(props, fixProps, repos, workspace, agent, guard, verifier, new ObjectMapper());
        when(workspace.prepare(anyString(), anyString())).thenReturn(ws);
        when(repos.defaultBranch(anyString())).thenReturn("main");
        when(repos.branchHead(anyString(), anyString())).thenReturn(Optional.empty());
        when(repos.listPulls(anyString(), anyString())).thenReturn(List.of());
        when(repos.submodulePin(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
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
                .thenReturn(new PullInfo(9, "t", "b", "https://gh/HE-KMS/pull/9", "h", false));

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
                new PullInfo(5, "fix(deps): bump x/net", "GO-2026-6860 해결", "https://gh/envector/pull/5", "h", false)));

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
                .thenReturn(new PullInfo(3, "t", "b", "u", "h", false));

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

    @Test
    void ownerFixAlreadyMerged_bumpsTheSubmoduleInTheAlertRepoInstead() {
        when(repos.submodulePin("envector", "external/HE-KMS", "main")).thenReturn(Optional.of("1".repeat(40)));
        when(repos.listPulls("HE-KMS", "closed")).thenReturn(List.of(
                new PullInfo(9, "fix(deps)", "...\n<!-- sol-sbom-fix vulns=GO-2026-6860 -->\n", "https://gh/HE-KMS/pull/9", "h", true)));
        when(repos.branchHead("HE-KMS", "main")).thenReturn(Optional.of("2".repeat(40)));
        when(repos.createPull(eq("envector"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new PullInfo(11, "t", "b", "https://gh/envector/pull/11", "h", false));

        SbomFixService.RepoResult r = service.fix(1, ALERT, report(xnet("envector-kms-crypto")), SbomFixServiceTest::owner, false).get(0);

        assertThat(r.status()).isEqualTo(SbomFixService.Status.SUBMODULE_PR);
        assertThat(r.repo()).isEqualTo("envector");
        verify(repos).commitGitlink(eq("envector"), eq("main"), eq("external/HE-KMS"), eq("2".repeat(40)), anyString(),
                eq("bot/sbom-pin-he-kms-2222222"));
        verify(agent, never()).run(any(), anyString(), anyString());
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
                new SbomFixService.RepoResult("heracles", SbomFixService.Status.FAILED, "", "a <b>")));

        assertThat(text).isEqualTo("🔧 *자동 수정 PR*\n• *HE-KMS* — <https://gh/1|#1 fix>\n"
                + "• *envector* — 🔁 이미 진행 중인 PR <https://gh/2|#2>\n• *heracles* — ⚠️ 실패: a &lt;b&gt;");
        assertThat(SbomFixService.title("", List.of(xnet("a")))).isEqualTo("fix(deps): bump golang.org/x/net to 0.62.0 for GO-2026-6860");
        assertThat(SbomFixService.title("fix: " + "x".repeat(200), List.of(xnet("a")))).hasSize(100);
        assertThat(SbomFixService.slug("google.golang.org/grpc")).isEqualTo("grpc");
        assertThat(SbomFixService.markerIds("x <!-- sol-sbom-fix vulns=A,B --> y")).containsExactly("A", "B");
    }
}
