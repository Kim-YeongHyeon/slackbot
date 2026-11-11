package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.cialert.GitHubActionsClient.CommitInfo;
import com.jirabot.slack.cialert.GitHubActionsClient.JobInfo;
import com.jirabot.slack.cialert.GitHubActionsClient.RunInfo;
import com.jirabot.slack.cialert.GitHubActionsClient.RunSummary;
import com.jirabot.slack.cialert.GitHubActionsClient.StepInfo;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.SlackHistoryMessage;
import com.jirabot.slack.dto.SlackEventInner;
import com.jirabot.slack.entity.CiAlertEntity;
import com.jirabot.slack.entity.CiAlertEntity.Status;
import com.jirabot.slack.repository.CiAlertRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class CiAlertServiceTest {

    private static final String CHANNEL = "C0ALERT";
    private static final String ALERT_BOT = "B0ALARM";
    private static final String RUN_URL = "https://github.com/CryptoLabInc/evi/actions/runs/1000";
    private static final String ALERT_TEXT = ":rotating_light: *evi main failed on main* (CryptoLabInc/evi)\nrun: <"
            + RUN_URL + ">\n```fatal: cannot reach registry\n##[error]Process completed with exit code 1.\n```";

    private CiAlertRepository repository;
    private GitHubActionsClient github;
    private CiFailureAnalyzer analyzer;
    private SlackNotifier slack;
    private SbomArtifactReader sbomReader;
    private SbomFixService sbomFix;
    private CiCodeFixService codeFix;
    private CiAlertService service;
    private final Map<Long, CiAlertEntity> store = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static CiAlertProperties props(String token) {
        return new CiAlertProperties(true, CHANNEL, List.of(ALERT_BOT), "CryptoLabInc",
                List.of("he-kms", "envector", "evi", "heracles"), token, null, 24, 180, 60,
                List.of("envector-kms-crypto=HE-KMS"), null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(CiAlertRepository.class);
        github = mock(GitHubActionsClient.class);
        analyzer = mock(CiFailureAnalyzer.class);
        slack = mock(SlackNotifier.class);
        sbomReader = mock(SbomArtifactReader.class);
        sbomFix = mock(SbomFixService.class);
        codeFix = mock(CiCodeFixService.class);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(repository.save(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(repository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.get((Long) inv.getArgument(0))));
        when(slack.postThreadReplyTs(anyString(), anyString(), anyString())).thenReturn(Optional.of("reply.1"));
        service = new CiAlertService(props("tok"), repository, github, analyzer, slack, sbomReader, sbomFix, codeFix, Runnable::run);
    }

    private CiAlertEntity persist(CiAlertEntity e) {
        if (e.getId() == null) {
            ReflectionTestUtils.setField(e, "id", seq.incrementAndGet());
        }
        store.put(e.getId(), e);
        return e;
    }

    private static String nowTs() {
        return Instant.now().getEpochSecond() + ".000100";
    }

    private static SlackEventInner alertEvent(String ts) {
        return new SlackEventInner("message", null, ALERT_TEXT, CHANNEL, ts, null, ALERT_BOT, null);
    }

    private void stubRun(String log) {
        when(github.getRun("evi", 1000)).thenReturn(new RunInfo(1000, "evi main", 77, "abcdef1234", "main",
                "push", "failure", 1, RUN_URL, "Fix it", "dev"));
        when(github.listJobs("evi", 1000, 1)).thenReturn(List.of(
                new JobInfo(1, "build", "success", List.of()),
                new JobInfo(2, "test (gpu)", "failure",
                        List.of(new StepInfo("Checkout", "success"), new StepInfo("Build", "failure")))));
        when(github.downloadJobLogTail(eq("evi"), eq(2L), anyInt())).thenReturn(log);
        when(github.getCommit("evi", "abcdef1234")).thenReturn(new CommitInfo("abcdef1234", "Fix it", "dev", List.of("a.cpp")));
        when(github.recentRuns("evi", 77, "main", 10)).thenReturn(List.of(
                new RunSummary(1000, "failure", "abcdef1234"), new RunSummary(990, "success", "x")));
    }

    private static CiAnalysis analysis() {
        return new CiAnalysis("INFRA", "high", "레지스트리 접속 실패예요.", "d", List.of("fatal: cannot reach registry"),
                List.of("re-run"));
    }

    // ── accepts ──────────────────────────────────────────────

    @Test
    void accepts_onlyTopLevelPostsFromAlertBotInAlertChannel() {
        String ts = nowTs();
        assertThat(service.accepts(alertEvent(ts))).isTrue();
        assertThat(service.accepts(new SlackEventInner("message", null, ALERT_TEXT, CHANNEL, ts, "bot_message", ALERT_BOT, null)))
                .isTrue();
        assertThat(service.accepts(new SlackEventInner("message", null, ALERT_TEXT, CHANNEL, ts, null, ALERT_BOT, ts)))
                .as("thread_ts == ts 는 스레드가 달린 부모 글").isTrue();
    }

    @Test
    void accepts_rejectsEverythingElse() {
        String ts = nowTs();
        assertThat(service.accepts(new SlackEventInner("message", null, ALERT_TEXT, "C_OTHER", ts, null, ALERT_BOT, null)))
                .as("다른 채널").isFalse();
        assertThat(service.accepts(new SlackEventInner("message", "U1", ALERT_TEXT, CHANNEL, ts, null, null, null)))
                .as("사람 글").isFalse();
        assertThat(service.accepts(new SlackEventInner("message", null, "reply", CHANNEL, "2.0", null, ALERT_BOT, "1.0")))
                .as("스레드 답글").isFalse();
        assertThat(service.accepts(new SlackEventInner("message", null, "x", CHANNEL, ts, null, "B0OURBOT", null)))
                .as("우리 봇 등 다른 앱").isFalse();
        assertThat(service.accepts(new SlackEventInner("message", null, "x", CHANNEL, ts, "message_changed", ALERT_BOT, null)))
                .as("수정 이벤트").isFalse();
        assertThat(service.accepts(new SlackEventInner("app_mention", "U1", "<@B> hi", CHANNEL, ts, null, null, null)))
                .as("멘션").isFalse();
        assertThat(service.accepts(null)).isFalse();
        assertThat(service.accepts(new SlackEventInner("message", null,
                ":white_check_mark: evi nightly green\nrun: <" + RUN_URL + ">", CHANNEL, ts, null, ALERT_BOT, null)))
                .as("알림 앱의 nightly 성공 글 (run 링크 있음)").isFalse();
        assertThat(service.accepts(new SlackEventInner("message", null,
                ":hourglass_flowing_sand: evi CI — 2 job(s) waiting over 30 min for a runner\nrun: <" + RUN_URL + ">",
                CHANNEL, ts, null, ALERT_BOT, null))).as("러너 대기 알림").isFalse();
        CiAlertService off = new CiAlertService(props(""), repository, github, analyzer, slack, sbomReader, sbomFix, codeFix, Runnable::run);
        assertThat(off.accepts(alertEvent(ts))).as("토큰 없음 → 기능 꺼짐").isFalse();
    }

    // ── submit / process ─────────────────────────────────────

    @Test
    void newFailure_isAnalyzedAndRepliedInThread() {
        stubRun("2026-11-05T05:08:29.1Z step start\n2026-11-05T05:08:30.1Z fatal: cannot reach registry\n"
                + "2026-11-05T05:08:30.2Z ##[error]Process completed with exit code 1.\n");
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        String ts = nowTs();

        service.submit(alertEvent(ts));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), reply.capture());
        assertThat(reply.getValue()).contains("🤖 *CI 실패 분석* · evi · evi main", "직전 실행은 성공",
                "레지스트리 접속 실패예요.");
        ArgumentCaptor<CiFailureAnalyzer.Input> input = ArgumentCaptor.forClass(CiFailureAnalyzer.Input.class);
        verify(analyzer).analyze(input.capture());
        assertThat(input.getValue().failedJobs()).singleElement()
                .satisfies(j -> assertThat(j.excerpt()).contains("fatal: cannot reach registry").doesNotContain("2026-11-05T"));
        verify(slack).addReaction(CHANNEL, ts, "eyes");
        verify(slack).removeReaction(CHANNEL, ts, "eyes");
        verify(slack).addReaction(CHANNEL, ts, "white_check_mark");
        CiAlertEntity row = store.get(1L);
        assertThat(row.getStatus()).isEqualTo(Status.DONE);
        assertThat(row.getRepo()).isEqualTo("evi");
        assertThat(row.getKind()).isEqualTo("CI");
        assertThat(row.getReplyTs()).isEqualTo("reply.1");
        assertThat(row.getFingerprint()).hasSize(16);
        assertThat(row.getCategory()).isEqualTo("INFRA");
    }

    @Test
    void sameCauseWithinWindow_repliesWithLinkWithoutCallingClaude() {
        stubRun("##[error]boom\n");
        CiAlertEntity prior = new CiAlertEntity(CHANNEL, "1.0");
        prior.finish(Status.DONE, "fp", "INFRA", "레지스트리 접속 실패예요.", "prior.reply");
        when(repository.findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                eq(CHANNEL), eq("evi"), anyString(), eq(Status.DONE), any(), any())).thenReturn(Optional.of(prior));
        when(slack.getPermalink(CHANNEL, "prior.reply")).thenReturn(Optional.of("https://slack/p/prior"));
        String ts = nowTs();

        service.submit(alertEvent(ts));

        verify(analyzer, never()).analyze(any());
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), reply.capture());
        assertThat(reply.getValue()).startsWith("🔁 ").contains("<https://slack/p/prior|이전 분석>", "레지스트리 접속 실패예요.");
        verify(slack).addReaction(CHANNEL, ts, "repeat");
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.REPEAT);
    }

    @Test
    void duplicateEvent_isProcessedOnce() {
        // doThrow — when(...) 안에서 기존 Answer(persist)가 null 인자로 불리지 않게.
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup")).when(repository).saveAndFlush(any());

        service.submit(alertEvent(nowTs()));

        verify(github, never()).getRun(anyString(), anyLong());
    }

    @Test
    void oldEvent_isIgnored() {
        service.submit(alertEvent((Instant.now().getEpochSecond() - 2 * 3600) + ".0"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void githubForbidden_repliesWithReason() {
        when(github.getRun("evi", 1000)).thenThrow(new GitHubActionsException(403, "HTTP 403"));
        String ts = nowTs();

        service.submit(alertEvent(ts));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), reply.capture());
        assertThat(reply.getValue()).startsWith("⚠️ 자동 분석에 실패했어요: GitHub 권한이 없어요(403)");
        verify(slack).addReaction(CHANNEL, ts, "warning");
        verify(slack).removeReaction(CHANNEL, ts, "eyes");
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    void claudeFailure_repliesFailure() {
        stubRun("##[error]boom\n");
        when(analyzer.analyze(any())).thenReturn(Optional.empty());

        service.submit(alertEvent(nowTs()));

        verify(slack).postThreadReplyTs(eq(CHANNEL), anyString(), org.mockito.ArgumentMatchers.startsWith("⚠️"));
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    void logDownloadFailure_stillAnalyzesWithPlaceholder() {
        stubRun("");
        when(github.downloadJobLogTail(eq("evi"), eq(2L), anyInt())).thenThrow(new GitHubActionsException(410, "gone"));
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));

        service.submit(alertEvent(nowTs()));

        ArgumentCaptor<CiFailureAnalyzer.Input> input = ArgumentCaptor.forClass(CiFailureAnalyzer.Input.class);
        verify(analyzer).analyze(input.capture());
        assertThat(input.getValue().failedJobs().get(0).excerpt()).isEqualTo("(로그를 받지 못함)");
        assertThat(input.getValue().alertText()).contains("fatal: cannot reach registry");
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.DONE);
    }

    @Test
    void messageWithoutTargetRunLink_isSkippedSilently() {
        SlackEventInner e = new SlackEventInner("message", null, "점검 안내입니다", CHANNEL, nowTs(), null, ALERT_BOT, null);

        service.submit(e);

        assertThat(store.get(1L).getStatus()).isEqualTo(Status.SKIPPED);
        verify(slack, never()).postThreadReplyTs(anyString(), anyString(), anyString());
        verify(slack, never()).addReaction(anyString(), anyString(), anyString());
    }

    @Test
    void runWithoutFailedJobs_successOrInProgress_isSkippedWithoutReply() {
        for (String conclusion : new String[] {"success", null}) {
            when(github.getRun("evi", 1000)).thenReturn(new RunInfo(1000, "evi main", 77, "abcdef1234", "main",
                    "push", conclusion, 1, RUN_URL, "Fix it", "dev"));
            when(github.listJobs("evi", 1000, 1)).thenReturn(List.of(new JobInfo(1, "build", "success", List.of()),
                    new JobInfo(2, "test", null, List.of())));

            service.submit(alertEvent(nowTs()));
        }

        assertThat(store.values()).extracting(CiAlertEntity::getStatus).containsOnly(Status.SKIPPED);
        verify(analyzer, never()).analyze(any());
        verify(slack, never()).postThreadReplyTs(anyString(), anyString(), anyString());
    }

    @Test
    void failedRunWithoutFailedJobs_isStillAnalyzedFromTheAlertText() {
        stubRun("");
        when(github.getRun("evi", 1000)).thenReturn(new RunInfo(1000, "evi main", 77, "abcdef1234", "main",
                "push", "startup_failure", 1, RUN_URL, "Fix it", "dev"));
        when(github.listJobs("evi", 1000, 1)).thenReturn(List.of());
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));

        service.submit(alertEvent(nowTs()));

        verify(analyzer).analyze(any());
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.DONE);
    }

    // ── replay ───────────────────────────────────────────────

    @Test
    void dryRunReplay_sendsDmOnlyAndKeepsChannelUntouched() {
        stubRun("##[error]boom\n");
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        when(slack.getMessage(CHANNEL, "1.5")).thenReturn(Optional.of(
                new SlackHistoryMessage("1.5", ALERT_TEXT, null, ALERT_BOT, null, null)));

        service.replay("1.5", "U_ME");

        ArgumentCaptor<String> dm = ArgumentCaptor.forClass(String.class);
        verify(slack).sendDirectMessage(eq("U_ME"), dm.capture());
        assertThat(dm.getValue()).startsWith("🧪 *재처리 테스트*").contains("CI 실패 분석");
        verify(slack, never()).postThreadReplyTs(anyString(), anyString(), anyString());
        verify(slack, never()).addReaction(anyString(), anyString(), anyString());
        assertThat(store.get(1L).getChannel()).isEqualTo("DM:U_ME");
        assertThat(store.get(1L).getMessageTs()).startsWith("1.5#");
    }

    @Test
    void replay_rejectsNonAlertMessages_andDisabledFeature() {
        when(slack.getMessage(CHANNEL, "2.0")).thenReturn(Optional.of(
                new SlackHistoryMessage("2.0", "hello", "U1", null, null, null)));

        assertThatThrownBy(() -> service.replay("2.0", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replay("3.0", null)).isInstanceOf(IllegalArgumentException.class);
        CiAlertService off = new CiAlertService(props(""), repository, github, analyzer, slack, sbomReader, sbomFix, codeFix, Runnable::run);
        assertThatThrownBy(() -> off.replay("2.0", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realReplay_reusesExistingRow() {
        stubRun("##[error]boom\n");
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        CiAlertEntity old = persist(new CiAlertEntity(CHANNEL, "1.5"));
        old.fail(Status.FAILED, "x");
        when(repository.findByChannelAndMessageTs(CHANNEL, "1.5")).thenReturn(Optional.of(old));
        when(slack.getMessage(CHANNEL, "1.5")).thenReturn(Optional.of(
                new SlackHistoryMessage("1.5", ALERT_TEXT, null, ALERT_BOT, null, null)));

        long id = service.replay("1.5", null);

        assertThat(id).isEqualTo(old.getId());
        assertThat(old.getStatus()).isEqualTo(Status.DONE);
        assertThat(old.getError()).isNull();
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq("1.5"), anyString());
    }

    // ── SBOM (v0.0.92) ───────────────────────────────────────

    private static final String SBOM_RUN_URL = "https://github.com/CryptoLabInc/envector/actions/runs/2000";
    private static final String SBOM_TEXT = ":rotating_light: *SBOM pipeline failed on main*\nrun: <" + SBOM_RUN_URL
            + ">\n```[0003] ERROR discovered vulnerabilities at or above the severity threshold\n```\n"
            + "To fix locally: run `/fix-sbom-cve <" + SBOM_RUN_URL + ">` in Claude Code";

    private void stubSbomRun(String failedStep) {
        when(github.getRun("envector", 2000)).thenReturn(new RunInfo(2000, "Release", 88, "feedbeef99", "main",
                "push", "failure", 1, SBOM_RUN_URL, "chore", "dev"));
        when(github.listJobs("envector", 2000, 1)).thenReturn(List.of(new JobInfo(5, "build-docker-image (linux)", "failure",
                List.of(new StepInfo("Build images", "Build images".equals(failedStep) ? "failure" : "success"),
                        new StepInfo("Generate SBOM and Grype JSON",
                                "Generate SBOM and Grype JSON".equals(failedStep) ? "failure" : "skipped")))));
        when(github.downloadJobLogTail(eq("envector"), eq(5L), anyInt())).thenReturn("##[error]boom\n");
        when(github.getCommit(anyString(), anyString())).thenReturn(new CommitInfo("feedbeef99", "chore", "dev", List.of()));
    }

    private static GrypeReport report() {
        return new GrypeReport(List.of(
                new GrypeReport.Finding("CVE-2026-0001", "Critical", "github.com/acme/jose", "v4.1.5", "go-module",
                        List.of("v4.1.6"), "fixed", 9.1, java.util.Set.of(), new java.util.TreeSet<>(List.of("envector-kms-crypto")),
                        new java.util.TreeSet<>(List.of("amd64"))),
                new GrypeReport.Finding("CVE-2026-0002", "High", "libfoo", "1.0", "deb",
                        List.of(), "not-fixed", 5.0, java.util.Set.of(), new java.util.TreeSet<>(List.of("envector-backend")),
                        new java.util.TreeSet<>(List.of("arm64")))), 2);
    }

    @Test
    void sbomScanFailure_withGrypeArtifact_repliesVulnerabilityTableWithoutClaude() {
        stubSbomRun("Generate SBOM and Grype JSON");
        when(sbomReader.read("envector", 2000)).thenReturn(Optional.of(report()));
        String ts = nowTs();

        service.submit(new SlackEventInner("message", null, SBOM_TEXT, CHANNEL, ts, null, ALERT_BOT, null));

        verify(analyzer, never()).analyze(any());
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), reply.capture());
        assertThat(reply.getValue()).startsWith("🛡️ *SBOM 취약점 분석* · envector · Release")
                .contains("*HE-KMS* — envector-kms-crypto", "`github.com/acme/jose` v4.1.5 → *v4.1.6* · Critical 1 — `CVE-2026-0001`",
                        "⚠️ *고칠 버전 없음*", "`.grype.yaml` 예외 후보");
        CiAlertEntity row = store.get(1L);
        assertThat(row.getStatus()).isEqualTo(Status.DONE);
        assertThat(row.getKind()).isEqualTo("SBOM");
        assertThat(row.getCategory()).isEqualTo("VULNERABILITY");
        assertThat(row.getVulnIds()).isEqualTo("CVE-2026-0001,CVE-2026-0002");
        assertThat(row.getSummary()).startsWith("High/Critical 취약점 2개: CVE-2026-0001");
        verify(slack).addReaction(CHANNEL, ts, "white_check_mark");
    }

    @Test
    void sbomScanFailure_withoutArtifact_fallsBackToLogAnalysis() {
        stubSbomRun("Generate SBOM and Grype JSON");
        when(sbomReader.read("envector", 2000)).thenReturn(Optional.empty());
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));

        service.submit(new SlackEventInner("message", null, SBOM_TEXT, CHANNEL, nowTs(), null, ALERT_BOT, null));

        verify(analyzer).analyze(any());
        verify(slack).postThreadReplyTs(eq(CHANNEL), anyString(), org.mockito.ArgumentMatchers.startsWith("🤖 *CI 실패 분석*"));
    }

    @Test
    void sbomAlertCausedByBuildFailure_skipsArtifactsAndAnalyzesLogs() {
        stubSbomRun("Build images");
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));

        service.submit(new SlackEventInner("message", null, SBOM_TEXT, CHANNEL, nowTs(), null, ALERT_BOT, null));

        verify(sbomReader, never()).read(anyString(), anyLong());
        verify(analyzer).analyze(any());
    }

    @Test
    void sameVulnerabilitySetAgain_repliesRepeatLink() {
        stubSbomRun("Generate SBOM and Grype JSON");
        when(sbomReader.read("envector", 2000)).thenReturn(Optional.of(report()));
        CiAlertEntity prior = new CiAlertEntity(CHANNEL, "1.0");
        prior.finish(Status.DONE, "fp", "VULNERABILITY", "High/Critical 2건: CVE-2026-0001, CVE-2026-0002", "prior.reply");
        when(repository.findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                eq(CHANNEL), eq("envector"), anyString(), eq(Status.DONE), any(), any())).thenReturn(Optional.of(prior));

        service.submit(new SlackEventInner("message", null, SBOM_TEXT, CHANNEL, nowTs(), null, ALERT_BOT, null));

        verify(slack).postThreadReplyTs(eq(CHANNEL), anyString(), org.mockito.ArgumentMatchers.startsWith("🔁 "));
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.REPEAT);
        assertThat(store.get(1L).getVulnIds()).isEqualTo("CVE-2026-0001,CVE-2026-0002");
    }

    @Test
    void ciTitledAlert_failedInGrypeStep_stillGetsVulnerabilityTable() {
        // HE-KMS release 처럼 SBOM 표시 없는 일반 CI 알림 제목이어도, 실패한 step 이 grype 단계면 취약점 표
        when(github.getRun("evi", 1000)).thenReturn(new RunInfo(1000, "evi release", 77, "abcdef1234", "main",
                "workflow_run", "failure", 1, RUN_URL, "x", "dev"));
        when(github.listJobs("evi", 1000, 1)).thenReturn(List.of(new JobInfo(9, "verify", "failure",
                List.of(new StepInfo("Generate SBOM and Grype JSON", "failure")))));
        when(sbomReader.read("evi", 1000)).thenReturn(Optional.of(report()));
        String ts = nowTs();

        service.submit(alertEvent(ts));   // 제목: "*evi main failed on main*" — SBOM 문구 없음

        verify(analyzer, never()).analyze(any());
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), org.mockito.ArgumentMatchers.startsWith("🛡️ *SBOM 취약점 분석* · evi"));
        assertThat(store.get(1L).getKind()).isEqualTo("SBOM");
    }

    @Test
    void replayRun_dryRunByRunLink_sendsDmWithoutAlertMessage() {
        stubRun("##[error]boom\n");
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));

        service.replayRun(RUN_URL, "U_ME");

        ArgumentCaptor<String> dm = ArgumentCaptor.forClass(String.class);
        verify(slack).sendDirectMessage(eq("U_ME"), dm.capture());
        assertThat(dm.getValue()).startsWith("🧪 *재처리 테스트* · run 링크로 직접 실행\n").contains("CI 실패 분석");
        verify(slack, never()).getPermalink(anyString(), any());
        assertThat(store.get(1L).getChannel()).isEqualTo("DM:U_ME");
        assertThat(store.get(1L).getMessageTs()).startsWith("run:1000#");
    }

    @Test
    void replayRun_requiresDryRunUserAndTargetRepoLink() {
        assertThatThrownBy(() -> service.replayRun(RUN_URL, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replayRun("https://github.com/other/repo/actions/runs/1", "U"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sbomScanFailed_detectsOnlyScanSteps() {
        JobInfo scan = new JobInfo(1, "b", "failure", List.of(new StepInfo("NVD CVSS gate (via Grype JSON)", "failure")));
        JobInfo push = new JobInfo(2, "b", "failure", List.of(new StepInfo("Push SBOM to OCIR", "failure")));

        assertThat(CiAlertService.sbomScanFailed(List.of(push, scan))).isTrue();
        assertThat(CiAlertService.sbomScanFailed(List.of(push))).isFalse();
        assertThat(CiAlertService.sbomScanFailed(List.of())).isFalse();
    }

    // ── 자동 수정 (v0.0.94) ─────────────────────────────────

    @Test
    void sbomTable_thenFixRuns_andPlaceholderIsReplacedWithResults() {
        stubSbomRun("Generate SBOM and Grype JSON");
        when(sbomReader.read("envector", 2000)).thenReturn(Optional.of(report()));
        when(sbomFix.enabled()).thenReturn(true);
        when(slack.postThreadReplyTs(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("table.ts"), Optional.of("placeholder.ts"));
        when(sbomFix.fix(anyLong(), any(), any(), any(), eq(false))).thenReturn(List.of(
                new SbomFixService.RepoResult("HE-KMS", SbomFixService.Status.CREATED, "https://gh/HE-KMS/pull/9", "#9 fix(deps): bump"),
                new SbomFixService.RepoResult("envector", SbomFixService.Status.SUBMODULE_MISSING,
                        "https://gh/envector/actions/workflows/update_submodule.yaml", "업데이트 PR 이 없어요")));
        String ts = nowTs();

        service.submit(new SlackEventInner("message", null, SBOM_TEXT, CHANNEL, ts, null, ALERT_BOT, null));

        ArgumentCaptor<String> replies = ArgumentCaptor.forClass(String.class);
        verify(slack, org.mockito.Mockito.times(2)).postThreadReplyTs(eq(CHANNEL), eq(ts), replies.capture());
        assertThat(replies.getAllValues().get(0)).contains("수정 PR 을 자동으로 만들어요");
        assertThat(replies.getAllValues().get(1)).startsWith("🔧 수정 PR 만드는 중");
        ArgumentCaptor<String> updated = ArgumentCaptor.forClass(String.class);
        verify(slack).updateMessage(eq(CHANNEL), eq("placeholder.ts"), updated.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(updated.getValue()).contains("<https://gh/HE-KMS/pull/9|#9 fix(deps): bump>", "update_submodule 실행");
        assertThat(store.get(1L).getPrUrls()).as("워크플로 링크는 PR 이 아니라 남기지 않는다").isEqualTo("https://gh/HE-KMS/pull/9");
    }

    @Test
    void fixDisabled_tableSaysSo_andNoFixRuns() {
        stubSbomRun("Generate SBOM and Grype JSON");
        when(sbomReader.read("envector", 2000)).thenReturn(Optional.of(report()));
        when(sbomFix.enabled()).thenReturn(false);

        service.submit(new SlackEventInner("message", null, SBOM_TEXT, CHANNEL, nowTs(), null, ALERT_BOT, null));

        verify(slack).postThreadReplyTs(eq(CHANNEL), anyString(), org.mockito.ArgumentMatchers.contains("자동 수정이 꺼져 있어요"));
        verify(sbomFix, never()).fix(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void dryRunReplay_fixesOnlyWhenAsked_andWithoutPush() {
        stubSbomRun("Generate SBOM and Grype JSON");
        when(sbomReader.read("envector", 2000)).thenReturn(Optional.of(report()));
        when(sbomFix.enabled()).thenReturn(true);
        when(sbomFix.fix(anyLong(), any(), any(), any(), eq(true))).thenReturn(List.of(
                new SbomFixService.RepoResult("HE-KMS", SbomFixService.Status.DRY_RUN, "", "diff")));
        String runUrl = "https://github.com/CryptoLabInc/envector/actions/runs/2000";

        service.replayRun(runUrl, "U_ME");                // fix 없이
        verify(sbomFix, never()).fix(anyLong(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

        service.replayRun(runUrl, "U_ME", true);          // fix 요청
        verify(sbomFix).fix(anyLong(), any(), any(), any(), eq(true));
        verify(slack, org.mockito.Mockito.atLeastOnce()).sendDirectMessage(eq("U_ME"), org.mockito.ArgumentMatchers.contains("🧪 dry-run"));
        verify(slack, never()).updateMessage(anyString(), anyString(), anyString(), any());
    }

    // ── 연속 실패 자동 수정 (v0.0.100) ───────────────────────

    private void stubStreak(int failures) {
        List<RunSummary> runs = new java.util.ArrayList<>();
        for (int i = 0; i < failures; i++) {
            runs.add(new RunSummary(1000 - i * 10L, "failure", "s" + i));
        }
        runs.add(new RunSummary(900, "success", "ok"));
        when(github.recentRuns("evi", 77, "main", 10)).thenReturn(runs);
    }

    private static CiCodeFixService.Result created() {
        return new CiCodeFixService.Result(CiCodeFixService.Status.CREATED, "https://gh/evi/pull/12", "#12 fix(ci): mount",
                "마운트 경로가 틀렸어요.", "-v 경로를 바꿨어요.", List.of(), false);
    }

    @Test
    void thirdConsecutiveFailure_runsFixAfterAnalysis_andPlaceholderBecomesTheResult() {
        stubRun("##[error]boom\n");
        stubStreak(3);
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        when(codeFix.wants(3, false, false)).thenReturn(true);
        when(codeFix.keyOf(any())).thenReturn("evi/77@main");
        when(codeFix.existing(any())).thenReturn(Optional.empty());
        when(codeFix.fix(any(), any(), eq("INFRA"), eq("레지스트리 접속 실패예요."))).thenReturn(created());
        when(slack.postThreadReplyTs(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("analysis.ts"), Optional.of("placeholder.ts"));
        String ts = nowTs();

        service.submit(alertEvent(ts));

        ArgumentCaptor<String> replies = ArgumentCaptor.forClass(String.class);
        verify(slack, org.mockito.Mockito.times(2)).postThreadReplyTs(eq(CHANNEL), eq(ts), replies.capture());
        assertThat(replies.getAllValues().get(0)).contains("3번 연속 실패");
        assertThat(replies.getAllValues().get(1)).startsWith("🔧 3번 연속 실패라 원인을 다시 보고");
        ArgumentCaptor<String> updated = ArgumentCaptor.forClass(String.class);
        verify(slack).updateMessage(eq(CHANNEL), eq("placeholder.ts"), updated.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(updated.getValue()).contains("<https://gh/evi/pull/12|#12 fix(ci): mount>", "마운트 경로가 틀렸어요.");
        ArgumentCaptor<CiCodeFixService.Request> req = ArgumentCaptor.forClass(CiCodeFixService.Request.class);
        verify(codeFix).fix(req.capture(), any(), anyString(), anyString());
        assertThat(req.getValue().streak()).isEqualTo(3);
        assertThat(req.getValue().channel()).isEqualTo(CHANNEL);
        assertThat(req.getValue().dryRun()).isFalse();
        CiAlertEntity row = store.get(1L);
        assertThat(row.getStatus()).as("분석 결과는 그대로").isEqualTo(Status.DONE);
        assertThat(row.getFixKey()).isEqualTo("evi/77@main");
        assertThat(row.getFixStatus()).isEqualTo("CREATED");
        assertThat(row.getFixDetail()).isEqualTo("마운트 경로가 틀렸어요.");
        assertThat(row.getPrUrls()).isEqualTo("https://gh/evi/pull/12");
    }

    @Test
    void secondConsecutiveFailure_doesNotStartTheFix() {
        stubRun("##[error]boom\n");
        stubStreak(2);
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));

        service.submit(alertEvent(nowTs()));

        verify(codeFix).wants(2, false, false);
        verify(codeFix, never()).existing(any());
        verify(codeFix, never()).fix(any(), any(), any(), any());
        assertThat(store.get(1L).getFixStatus()).isNull();
    }

    @Test
    void vulnerabilityCategory_isLeftToTheSbomFix() {
        stubRun("##[error]boom\n");
        stubStreak(4);
        when(analyzer.analyze(any())).thenReturn(Optional.of(
                new CiAnalysis("VULNERABILITY", "high", "grype 게이트 실패예요.", "d", List.of(), List.of())));
        when(codeFix.wants(4, false, false)).thenReturn(true);

        service.submit(alertEvent(nowTs()));

        verify(codeFix, never()).existing(any());
        verify(codeFix, never()).fix(any(), any(), any(), any());
    }

    @Test
    void repeatAlertOnThirdFailure_startsTheFixToo_withThePriorAnalysis() {
        stubRun("##[error]boom\n");
        stubStreak(3);
        CiAlertEntity prior = new CiAlertEntity(CHANNEL, "1.0");
        prior.finish(Status.DONE, "fp", "CONFIG", "마운트 경로 문제예요.", "prior.reply");
        when(repository.findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                eq(CHANNEL), eq("evi"), anyString(), eq(Status.DONE), any(), any())).thenReturn(Optional.of(prior));
        when(codeFix.wants(3, false, false)).thenReturn(true);
        when(codeFix.keyOf(any())).thenReturn("evi/77@main");
        when(codeFix.existing(any())).thenReturn(Optional.empty());
        when(codeFix.fix(any(), any(), eq("CONFIG"), eq("마운트 경로 문제예요."))).thenReturn(created());

        service.submit(alertEvent(nowTs()));

        verify(analyzer, never()).analyze(any());
        ArgumentCaptor<CiFailureAnalyzer.Input> input = ArgumentCaptor.forClass(CiFailureAnalyzer.Input.class);
        verify(codeFix).fix(any(), input.capture(), eq("CONFIG"), eq("마운트 경로 문제예요."));
        assertThat(input.getValue().recentRuns()).isEqualTo("3번 연속 실패");
        assertThat(input.getValue().failedJobs()).singleElement().satisfies(j -> assertThat(j.excerpt()).contains("boom"));
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.REPEAT);
        assertThat(store.get(1L).getFixStatus()).isEqualTo("CREATED");
    }

    @Test
    void earlierAttempt_isLinkedWithoutPlaceholderOrAgent_andNotRecordedAgain() {
        stubRun("##[error]boom\n");
        stubStreak(5);
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        when(codeFix.wants(5, false, false)).thenReturn(true);
        when(codeFix.keyOf(any())).thenReturn("evi/77@main");
        when(codeFix.existing(any())).thenReturn(Optional.of(new CiCodeFixService.Result(CiCodeFixService.Status.CREATED,
                "https://gh/evi/pull/12", "#12", "", "", List.of(), true)));
        String ts = nowTs();

        service.submit(alertEvent(ts));

        verify(codeFix, never()).fix(any(), any(), any(), any());
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), org.mockito.ArgumentMatchers.contains("수정 PR <https://gh/evi/pull/12|#12> 이 올라와 있어요"));
        verify(slack, never()).updateMessage(anyString(), anyString(), anyString(), any());
        assertThat(store.get(1L).getFixStatus()).as("다시 보여 준 결과는 새 시도로 남기지 않는다").isNull();
    }

    @Test
    void fixStepThrows_analysisReplyStays_andFailureIsReported() {
        stubRun("##[error]boom\n");
        stubStreak(3);
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        when(codeFix.wants(3, false, false)).thenReturn(true);
        when(codeFix.keyOf(any())).thenReturn("evi/77@main");
        when(codeFix.existing(any())).thenThrow(new GitHubActionsException(502, "bad gateway"));
        String ts = nowTs();

        service.submit(alertEvent(ts));

        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), org.mockito.ArgumentMatchers.contains("🤖 *CI 실패 분석*"));
        verify(slack).postThreadReplyTs(eq(CHANNEL), eq(ts), org.mockito.ArgumentMatchers.contains("수정 PR 을 만들지 못했어요: bad gateway"));
        assertThat(store.get(1L).getStatus()).isEqualTo(Status.DONE);
        assertThat(store.get(1L).getFixStatus()).isEqualTo("FAILED");
        verify(slack).addReaction(CHANNEL, ts, "white_check_mark");
    }

    @Test
    void dryRunReplay_runsTheFixOnlyWhenAsked_andRepliesByDm() {
        stubRun("##[error]boom\n");
        stubStreak(3);
        when(analyzer.analyze(any())).thenReturn(Optional.of(analysis()));
        when(codeFix.wants(3, true, true)).thenReturn(true);
        when(codeFix.keyOf(any())).thenReturn("evi/77@main");
        when(codeFix.existing(any())).thenReturn(Optional.empty());
        when(codeFix.fix(any(), any(), anyString(), anyString())).thenReturn(
                CiCodeFixService.Result.of(CiCodeFixService.Status.DRY_RUN, "파일 scripts/build.sh"));

        service.replayRun(RUN_URL, "U_ME");
        verify(codeFix).wants(3, true, false);
        verify(codeFix, never()).fix(any(), any(), any(), any());

        service.replayRun(RUN_URL, "U_ME", true);
        ArgumentCaptor<CiCodeFixService.Request> req = ArgumentCaptor.forClass(CiCodeFixService.Request.class);
        verify(codeFix).fix(req.capture(), any(), anyString(), anyString());
        assertThat(req.getValue().dryRun()).isTrue();
        assertThat(req.getValue().force()).isTrue();
        assertThat(req.getValue().channel()).isEqualTo("DM:U_ME");
        verify(slack, org.mockito.Mockito.atLeastOnce()).sendDirectMessage(eq("U_ME"), org.mockito.ArgumentMatchers.contains("🧪 dry-run"));
        verify(slack, never()).postThreadReplyTs(anyString(), anyString(), anyString());
        verify(slack, never()).updateMessage(anyString(), anyString(), anyString(), any());
    }

    // ── runStatus ────────────────────────────────────────────

    private static RunInfo run(long id) {
        return new RunInfo(id, "wf", 5, "sha", "main", "push", "failure", 1, "u", "", "");
    }

    @Test
    void runStatus_countsConsecutiveFailuresSkippingCancelled() {
        when(github.recentRuns("evi", 5, "main", 10)).thenReturn(List.of(
                new RunSummary(11, "success", "n"),   // 이번 run 보다 나중 — 무시
                new RunSummary(10, "failure", "a"),
                new RunSummary(9, "failure", "b"),
                new RunSummary(8, "cancelled", "c"),
                new RunSummary(7, "timed_out", "d"),
                new RunSummary(6, "success", "e")));

        assertThat(service.runStatus("evi", run(10))).isEqualTo("3번 연속 실패");
    }

    @Test
    void runStatus_previousSuccess_unknownHistory_andApiError() {
        when(github.recentRuns("evi", 5, "main", 10)).thenReturn(List.of(
                new RunSummary(10, "failure", "a"), new RunSummary(9, "success", "b")));
        assertThat(service.runStatus("evi", run(10))).isEqualTo("직전 실행은 성공");

        when(github.recentRuns("evi", 5, "main", 10)).thenReturn(List.of(
                new RunSummary(10, "failure", "a"), new RunSummary(9, "failure", "b")));
        assertThat(service.runStatus("evi", run(10))).isEqualTo("2번 이상 연속 실패");

        when(github.recentRuns("evi", 5, "main", 10)).thenReturn(List.of(new RunSummary(12, "failure", "z")));
        assertThat(service.runStatus("evi", run(10))).isEmpty();

        when(github.recentRuns("evi", 5, "main", 10)).thenThrow(new GitHubActionsException(500, "x"));
        assertThat(service.runStatus("evi", run(10))).isEmpty();
    }

    @Test
    void runStreak_countsThisRun_andSeesWhetherALaterRunPassed() {
        when(github.recentRuns("evi", 5, "main", 10)).thenReturn(List.of(
                new RunSummary(12, "cancelled", "n2"), new RunSummary(11, "success", "n1"),
                new RunSummary(10, "failure", "a"), new RunSummary(9, "failure", "b"), new RunSummary(8, "failure", "c")));
        CiAlertService.RunStreak recovered = service.runStreak("evi", run(10));
        assertThat(recovered.count()).isEqualTo(3);
        assertThat(recovered.closed()).isFalse();
        assertThat(recovered.recovered()).as("취소는 건너뛰고 그 앞의 성공을 본다").isTrue();

        when(github.recentRuns("evi", 5, "main", 10)).thenReturn(List.of(
                new RunSummary(12, "failure", "n2"), new RunSummary(11, "success", "n1"),
                new RunSummary(10, "failure", "a"), new RunSummary(9, "success", "b")));
        CiAlertService.RunStreak failingAgain = service.runStreak("evi", run(10));
        assertThat(failingAgain.count()).isEqualTo(1);
        assertThat(failingAgain.recovered()).as("가장 최근 실행이 다시 실패").isFalse();

        when(github.recentRuns("evi", 5, "main", 10)).thenThrow(new GitHubActionsException(500, "x"));
        assertThat(service.runStreak("evi", run(10)).count()).as("모르면 1 — 수정 단계가 돌지 않는다").isEqualTo(1);
    }
}
