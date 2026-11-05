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
    private CiAlertService service;
    private final Map<Long, CiAlertEntity> store = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static CiAlertProperties props(String token) {
        return new CiAlertProperties(true, CHANNEL, List.of(ALERT_BOT), "CryptoLabInc",
                List.of("he-kms", "envector", "evi", "heracles"), token, null, 24, 180, 60);
    }

    @BeforeEach
    void setUp() {
        repository = mock(CiAlertRepository.class);
        github = mock(GitHubActionsClient.class);
        analyzer = mock(CiFailureAnalyzer.class);
        slack = mock(SlackNotifier.class);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(repository.save(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(repository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.get((Long) inv.getArgument(0))));
        when(slack.postThreadReplyTs(anyString(), anyString(), anyString())).thenReturn(Optional.of("reply.1"));
        service = new CiAlertService(props("tok"), repository, github, analyzer, slack, Runnable::run);
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
        CiAlertService off = new CiAlertService(props(""), repository, github, analyzer, slack, Runnable::run);
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
        CiAlertService off = new CiAlertService(props(""), repository, github, analyzer, slack, Runnable::run);
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
}
