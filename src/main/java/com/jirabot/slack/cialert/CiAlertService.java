package com.jirabot.slack.cialert;

import com.jirabot.slack.cialert.GitHubActionsClient.CommitInfo;
import com.jirabot.slack.cialert.GitHubActionsClient.JobInfo;
import com.jirabot.slack.cialert.GitHubActionsClient.RunInfo;
import com.jirabot.slack.cialert.GitHubActionsClient.RunSummary;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.SlackHistoryMessage;
import com.jirabot.slack.config.AsyncConfig;
import com.jirabot.slack.dto.SlackEventInner;
import com.jirabot.slack.entity.CiAlertEntity;
import com.jirabot.slack.entity.CiAlertEntity.Status;
import com.jirabot.slack.repository.CiAlertRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// STUDY: #sol-dev-alarm 알림 자동 대응의 중심 (v0.0.91).
//        SlackEventController 는 accepts() 로 알림인지만 보고 submit() 에 넘긴 뒤 바로 200 을 준다 (Slack 3초 제한).
//        실제 처리(로그 다운로드 → 반복 판단 → Claude 분석 → 답글)는 ciAlertExecutor 단일 스레드에서 돈다.
@Service
public class CiAlertService {

    private static final Logger log = LoggerFactory.getLogger(CiAlertService.class);
    private static final DateTimeFormatter KST_TIME =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    static final int MAX_FAILED_JOBS = 5;
    static final int LOG_TAIL_BYTES = 2 * 1024 * 1024;
    static final int EXCERPT_PER_JOB = 12_000;
    static final int EXCERPT_TOTAL = 40_000;
    private static final int ALERT_TEXT_MAX = 6_000;
    private static final Duration STALE_PROCESSING = Duration.ofMinutes(30);
    // release.yaml 에서 grype 결과로 실패하는 step. 이 step 이 실패했을 때만 취약점 분석을 한다
    // (Build images·Push SBOM 실패는 취약점이 아니라 빌드·인프라 문제라 로그 분석으로 간다).
    static final Set<String> SBOM_SCAN_STEPS =
            Set.of("Generate SBOM and Grype JSON", "NVD CVSS gate (via Grype JSON)");
    // run 이 실패로 끝난 conclusion. 진행 중이면 null 이다.
    static final Set<String> FAILED_RUN_CONCLUSIONS = Set.of("failure", "timed_out", "startup_failure");

    private final CiAlertProperties props;
    private final CiAlertRepository repository;
    private final GitHubActionsClient github;
    private final CiFailureAnalyzer analyzer;
    private final SlackNotifier slack;
    private final SbomArtifactReader sbomReader;
    private final SbomFixService sbomFix;
    private final CiCodeFixService codeFix;
    private final Executor executor;

    public CiAlertService(CiAlertProperties props, CiAlertRepository repository, GitHubActionsClient github,
                          CiFailureAnalyzer analyzer, SlackNotifier slack, SbomArtifactReader sbomReader,
                          SbomFixService sbomFix, CiCodeFixService codeFix,
                          @Qualifier(AsyncConfig.CI_ALERT_EXECUTOR) Executor executor) {
        this.props = props;
        this.repository = repository;
        this.github = github;
        this.analyzer = analyzer;
        this.slack = slack;
        this.sbomReader = sbomReader;
        this.sbomFix = sbomFix;
        this.codeFix = codeFix;
        this.executor = executor;
    }

    // 답글 대상: 알림 스레드, 또는 재처리 테스트(dry-run)면 지정한 사용자의 DM.
    // fix: 재처리에서 자동 수정까지 돌릴지 (dry-run 이면 push 없이 diff 만 DM 으로).
    // atRunCommit: dry-run 에서 수정 에이전트를 브랜치 HEAD 대신 실패한 run 의 커밋에서 돌린다 (v0.0.103, 이미 고쳐진 뒤 시험용).
    record Target(String channel, String alertTs, String dryRunUser, boolean fix, boolean atRunCommit) {
        Target(String channel, String alertTs, String dryRunUser) {
            this(channel, alertTs, dryRunUser, false, false);
        }

        Target(String channel, String alertTs, String dryRunUser, boolean fix) {
            this(channel, alertTs, dryRunUser, fix, false);
        }

        boolean dryRun() {
            return dryRunUser != null;
        }

        // 반복 판단을 실제 알림과 테스트끼리 나눠서 한다 — 테스트 결과가 실제 알림을 "반복"으로 만들지 않게.
        String rowChannel() {
            return dryRun() ? "DM:" + dryRunUser : channel;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.enabled()) {
            log.info("ci-alert disabled (ci-alert.enabled=false)");
            return;
        }
        if (!props.effectivelyEnabled()) {
            log.warn("ci-alert is OFF: channel/alert-bot-ids/GITHUB_ALERT_TOKEN must all be set");
            return;
        }
        Instant now = Instant.now();
        int reset = repository.failStale(Status.PROCESSING, Status.FAILED, "서버 재시작으로 중단됨",
                now, now.minus(STALE_PROCESSING));
        log.info("ci-alert enabled channel={} repos={} alertBots={} (stale reset={})",
                props.channel(), props.repos(), props.alertBotIds(), reset);
    }

    // STUDY: 알림 판별 — 지정 채널에 알림 앱(bot_id)이 올린 "최상위" 글만. 사람 글, 스레드 답글(우리 봇 답글 포함),
    //        message_changed 같은 subtype 은 모두 false → 기존 app_mention 흐름으로 넘어가 거기서 무시된다.
    public boolean accepts(SlackEventInner e) {
        if (e == null || !props.effectivelyEnabled() || !"message".equals(e.type())) {
            return false;
        }
        boolean topLevel = e.thread_ts() == null || e.thread_ts().equals(e.ts());
        boolean plainBotPost = e.subtype() == null || "bot_message".equals(e.subtype());
        return props.channel().equals(e.channel()) && topLevel && plainBotPost
                && e.bot_id() != null && props.alertBotIds().contains(e.bot_id())
                && CiAlertParser.isFailureAlert(e.text());
    }

    public void submit(SlackEventInner e) {
        if (isTooOld(e.ts())) {
            log.info("ci-alert ignored: older than {}m ts={}", props.maxAlertAgeMinutes(), e.ts());
            return;
        }
        Long id = register(e.channel(), e.ts());
        if (id == null) {
            log.debug("ci-alert duplicate event ignored ts={}", e.ts());
            return;
        }
        dispatch(id, e.text(), new Target(e.channel(), e.ts(), null));
    }

    // 재처리 (관리자 API). dryRunUser 가 있으면 결과를 그 사용자 DM 으로만 보낸다.
    public long replay(String ts, String dryRunUser) {
        return replay(ts, dryRunUser, false);
    }

    public long replay(String ts, String dryRunUser, boolean fix) {
        return replay(ts, dryRunUser, fix, false);
    }

    public long replay(String ts, String dryRunUser, boolean fix, boolean atRunCommit) {
        if (!props.effectivelyEnabled()) {
            throw new IllegalStateException("ci-alert 기능이 꺼져 있어요 (GITHUB_ALERT_TOKEN 확인)");
        }
        if (atRunCommit && (dryRunUser == null || dryRunUser.isBlank())) {
            throw new IllegalArgumentException("atRunCommit 은 dry-run(dryRunUser)에서만 쓸 수 있어요");
        }
        SlackHistoryMessage m = slack.getMessage(props.channel(), ts)
                .orElseThrow(() -> new IllegalArgumentException("채널에서 메시지를 찾을 수 없어요: " + ts));
        if (m.botId() == null || !props.alertBotIds().contains(m.botId())) {
            throw new IllegalArgumentException("알림 앱이 올린 메시지가 아니에요: " + ts);
        }
        boolean dryRun = dryRunUser != null && !dryRunUser.isBlank();
        Target target = new Target(props.channel(), ts, dryRun ? dryRunUser : null, fix, atRunCommit);
        CiAlertEntity row;
        if (dryRun) {
            row = new CiAlertEntity(target.rowChannel(), ts + "#" + System.currentTimeMillis());
        } else {
            row = repository.findByChannelAndMessageTs(props.channel(), ts).orElse(null);
            if (row == null) {
                row = new CiAlertEntity(props.channel(), ts);
            } else {
                row.restart();
            }
        }
        long id = repository.saveAndFlush(row).getId();
        dispatch(id, m.text(), target);
        return id;
    }

    // 알림 메시지 없이 run 링크로 바로 돌려보는 테스트 (관리자 API, 결과는 DM 으로만).
    // 알림이 오지 않는 워크플로의 실패 run 으로 분석·취약점 표를 미리 확인할 때 쓴다.
    public long replayRun(String runUrl, String dryRunUser) {
        return replayRun(runUrl, dryRunUser, false);
    }

    public long replayRun(String runUrl, String dryRunUser, boolean fix) {
        return replayRun(runUrl, dryRunUser, fix, false);
    }

    public long replayRun(String runUrl, String dryRunUser, boolean fix, boolean atRunCommit) {
        if (!props.effectivelyEnabled()) {
            throw new IllegalStateException("ci-alert 기능이 꺼져 있어요 (GITHUB_ALERT_TOKEN 확인)");
        }
        if (dryRunUser == null || dryRunUser.isBlank()) {
            throw new IllegalArgumentException("run 링크로 돌릴 때는 dryRunUser 가 필요해요 (답글을 달 알림 스레드가 없어서)");
        }
        String text = "run: <" + runUrl + ">";
        CiAlert alert = CiAlertParser.parse(text, props.org(), props.repos())
                .orElseThrow(() -> new IllegalArgumentException("대상 레포의 GitHub Actions run 링크가 아니에요: " + runUrl));
        Target target = new Target(props.channel(), null, dryRunUser, fix, atRunCommit);
        long id = repository.saveAndFlush(new CiAlertEntity(target.rowChannel(),
                "run:" + alert.runId() + "#" + System.currentTimeMillis())).getId();
        dispatch(id, text, target);
        return id;
    }

    private Long register(String channel, String ts) {
        try {
            return repository.saveAndFlush(new CiAlertEntity(channel, ts)).getId();
        } catch (DataIntegrityViolationException dup) {
            // STUDY: (channel, message_ts) UNIQUE 위반 = 이미 받은 알림 (Slack 재전송). DB 가 중복을 막는다.
            return null;
        }
    }

    private void dispatch(long id, String text, Target target) {
        try {
            executor.execute(() -> process(id, text, target));
        } catch (RejectedExecutionException ex) {
            repository.findById(id).ifPresent(row -> {
                row.fail(Status.FAILED, "처리 대기열이 가득 참");
                repository.save(row);
            });
        }
    }

    void process(long id, String text, Target target) {
        CiAlertEntity row = repository.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        Optional<CiAlert> parsed = CiAlertParser.parse(text, props.org(), props.repos());
        if (parsed.isEmpty()) {
            row.fail(Status.SKIPPED, "대상 레포의 run 링크가 없는 메시지");
            repository.save(row);
            return;
        }
        CiAlert alert = parsed.get();
        row.identify(alert.repo(), alert.runId(), alert.kind().name());
        repository.save(row);
        react(target, "eyes");
        try {
            analyzeRun(row, alert, target);
        } catch (GitHubActionsException e) {
            fail(row, target, alert, githubReason(e));
        } catch (RuntimeException e) {
            log.warn("ci-alert processing failed id={}: {}", id, e.toString());
            fail(row, target, alert, "처리 중 오류 (" + e.getClass().getSimpleName() + ")");
        } finally {
            unreact(target, "eyes");
        }
    }

    private void analyzeRun(CiAlertEntity row, CiAlert alert, Target target) {
        String repo = alert.repo();
        RunInfo run = github.getRun(repo, alert.runId());
        List<JobInfo> allFailed = github.listJobs(repo, alert.runId(), run.runAttempt()).stream()
                .filter(JobInfo::failed).toList();
        // STUDY: 제목 필터를 지나도 GitHub 기준으로 한 번 더 본다 — 실패한 job 이 없고 run 도 실패로 끝나지 않았으면
        //        (성공·진행 중·재실행으로 통과) 분석할 게 없으니 답글 없이 건너뛴다. 실패 run 인데 job 이 없는 경우
        //        (startup_failure 등)는 예전처럼 알림 본문으로 분석한다.
        if (allFailed.isEmpty() && (run.conclusion() == null || !FAILED_RUN_CONCLUSIONS.contains(run.conclusion()))) {
            log.info("ci-alert run={} has no failed jobs (conclusion={}) — skipped", run.id(), run.conclusion());
            row.fail(Status.SKIPPED, "실패한 job 이 없는 run (conclusion=" + run.conclusion() + ")");
            repository.save(row);
            return;
        }
        // STUDY: 알림 제목이 아니라 GitHub 가 알려준 "실패한 step 이름"으로 판단한다 (v0.0.93).
        //        HE-KMS release 처럼 일반 CI 알림 제목으로 오는 grype 실패도 취약점 표로 답하기 위해서.
        // STUDY: Go 버전·govulncheck 검사 실패 (v0.0.103) — 연속 횟수와 상관없이 첫 알림부터 수정 단계를 돈다.
        //        SBOM 실패와 같이 오면 SBOM 답글 뒤에, 아니면 분석 답글 뒤에 (아래 codeFix 의 mode).
        List<String> goSteps = codeFix.goCheckSteps(allFailed.stream().flatMap(j -> j.failedStepNames().stream()).toList());
        if (sbomScanFailed(allFailed)) {
            Optional<GrypeReport> report = sbomReader.read(repo, run.id());
            if (report.isPresent() && !report.get().isEmpty()) {
                replySbom(row, alert, target, run, report.get());
                if (!goSteps.isEmpty()) {
                    goChecksAfterSbom(row, alert, target, run, allFailed, goSteps);
                }
                return;
            }
            log.info("ci-alert SBOM run={} has no grype artifact/findings — falling back to log analysis", run.id());
        }
        List<JobInfo> failed = allFailed.stream().limit(MAX_FAILED_JOBS).toList();

        List<CiFailureAnalyzer.FailedJob> jobs = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        List<String> errorLines = new ArrayList<>();
        int budget = EXCERPT_TOTAL;
        for (JobInfo j : failed) {
            List<String> stepNames = j.failedStepNames();
            if (stepNames.isEmpty()) {
                steps.add(j.name());
            } else {
                stepNames.forEach(s -> steps.add(j.name() + " → " + s));
            }
            if (budget <= 0) {
                continue;
            }
            List<String> lines = LogExcerpts.cleanLines(downloadLog(repo, j));
            errorLines.addAll(LogExcerpts.errorContext(lines));
            String excerpt = lines.isEmpty() ? "(로그를 받지 못함)" : LogExcerpts.excerpt(lines, Math.min(EXCERPT_PER_JOB, budget));
            budget -= excerpt.length();
            jobs.add(new CiFailureAnalyzer.FailedJob(j.name(), stepNames, excerpt));
        }
        if (errorLines.isEmpty()) {
            // 로그를 하나도 못 받으면 알림 원문 속 로그 일부로 지문을 만든다.
            errorLines.addAll(LogExcerpts.errorContext(LogExcerpts.cleanLines(alert.text())));
        }
        String fingerprint = LogExcerpts.fingerprint(repo, run.name(), steps, errorLines);

        // STUDY: 연속 실패 횟수는 반복 판단보다 먼저 구한다 (v0.0.100) — 2·3번째 실패는 "같은 실패예요" 답글로 끝나므로
        //        3번째에 수정 단계를 시작하려면 반복 경로에서도 횟수가 필요하다.
        RunStreak streak = runStreak(repo, run);
        // 커밋 조회까지 필요한 실패 정보는 분석이나 수정 단계가 실제로 쓸 때 만든다.
        Supplier<CiFailureAnalyzer.Input> inputOf = () -> {
            CommitInfo commit = commitOrEmpty(repo, run);
            return new CiFailureAnalyzer.Input(repo, run.name(), alert.runUrl(), run.headBranch(), run.headSha(),
                    commit.message().isBlank() ? run.commitMessage() : commit.message(),
                    commit.author().isBlank() ? run.commitAuthor() : commit.author(), commit.files(),
                    streak.label().isBlank() ? "(알 수 없음)" : streak.label(), jobs, cut(alert.text(), ALERT_TEXT_MAX));
        };

        CiCodeFixService.Mode mode = goSteps.isEmpty() ? CiCodeFixService.Mode.STREAK : CiCodeFixService.Mode.GO_CHECKS;
        // Go 검사 모드의 에이전트에게는 Go 검사 job 만 보여 준다 (같은 run 의 다른 실패는 이 수정의 범위가 아니다).
        Supplier<CiFailureAnalyzer.Input> fixInputOf = mode == CiCodeFixService.Mode.STREAK ? inputOf
                : () -> onlyJobs(inputOf.get(), goSteps);
        if (replyIfRepeat(row, target, repo, fingerprint)) {
            codeFix(row, alert, target, run, streak, fingerprint, fixInputOf, row.getCategory(), row.getSummary(), mode, goSteps);
            return;
        }

        CiFailureAnalyzer.Input input = inputOf.get();
        Optional<CiAnalysis> analysis = analyzer.analyze(input);
        if (analysis.isEmpty()) {
            fail(row, target, alert, "Claude 분석이 시간 안에 끝나지 않았거나 응답이 올바르지 않아요");
            return;
        }
        CiAnalysis a = analysis.get();
        Optional<String> ts = reply(target, CiReplyRenderer.analysis(repo, run.name(), a, streak.label(),
                run.headSha(), input.commitAuthor(), alert.runUrl()));
        row.finish(Status.DONE, fingerprint, a.category(), a.summary(), ts.orElse(null));
        repository.save(row);
        react(target, "white_check_mark");
        codeFix(row, alert, target, run, streak, fingerprint,
                mode == CiCodeFixService.Mode.STREAK ? () -> input : () -> onlyJobs(input, goSteps), a.category(), a.summary(),
                mode, goSteps);
    }

    private static CiFailureAnalyzer.Input onlyJobs(CiFailureAnalyzer.Input in, List<String> goSteps) {
        List<CiFailureAnalyzer.FailedJob> jobs = in.failedJobs().stream()
                .filter(j -> j.failedSteps().stream().anyMatch(goSteps::contains)).toList();
        return new CiFailureAnalyzer.Input(in.repo(), in.workflow(), in.runUrl(), in.branch(), in.headSha(),
                in.commitMessage(), in.commitAuthor(), in.changedFiles(), in.recentRuns(),
                jobs.isEmpty() ? in.failedJobs() : jobs, in.alertText());
    }

    // SBOM 답글 뒤의 Go 검사 수정 단계. SBOM 경로는 job 로그를 받지 않았으므로 Go 검사 job 로그만 따로 받는다.
    private void goChecksAfterSbom(CiAlertEntity row, CiAlert alert, Target target, RunInfo run, List<JobInfo> allFailed,
                                   List<String> goSteps) {
        if (!codeFix.wants(CiCodeFixService.Mode.GO_CHECKS, 0, target.dryRun(), target.fix())) {
            return;
        }
        String repo = alert.repo();
        List<CiFailureAnalyzer.FailedJob> jobs = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        List<String> errorLines = new ArrayList<>();
        for (JobInfo j : allFailed) {
            List<String> hit = j.failedStepNames().stream().filter(goSteps::contains).toList();
            if (hit.isEmpty()) {
                continue;
            }
            List<String> lines = LogExcerpts.cleanLines(downloadLog(repo, j));
            errorLines.addAll(LogExcerpts.errorContext(lines));
            hit.forEach(s -> steps.add(j.name() + " → " + s));
            jobs.add(new CiFailureAnalyzer.FailedJob(j.name(), j.failedStepNames(),
                    lines.isEmpty() ? "(로그를 받지 못함)" : LogExcerpts.excerpt(lines, EXCERPT_PER_JOB)));
        }
        String fingerprint = LogExcerpts.fingerprint(repo, "go-checks", steps, errorLines);
        RunStreak streak = runStreak(repo, run);
        Supplier<CiFailureAnalyzer.Input> input = () -> {
            CommitInfo commit = commitOrEmpty(repo, run);
            return new CiFailureAnalyzer.Input(repo, run.name(), alert.runUrl(), run.headBranch(), run.headSha(),
                    commit.message().isBlank() ? run.commitMessage() : commit.message(),
                    commit.author().isBlank() ? run.commitAuthor() : commit.author(), commit.files(),
                    streak.label().isBlank() ? "(알 수 없음)" : streak.label(), jobs, cut(alert.text(), ALERT_TEXT_MAX));
        };
        codeFix(row, alert, target, run, streak, fingerprint, input, "", "", CiCodeFixService.Mode.GO_CHECKS, goSteps);
    }

    // STUDY: 연속 실패 자동 수정 (v0.0.100). 이미 올라온 PR·앞선 시도가 있으면 그 링크만 달고, 없으면 "찾는 중"
    //        답글을 먼저 단 뒤 에이전트 결과로 바꾼다 (SBOM 의 runFix 와 같은 방식). 여기서 나는 오류는 분석 답글을 깨지 않는다.
    private void codeFix(CiAlertEntity row, CiAlert alert, Target target, RunInfo run, RunStreak streak, String fingerprint,
                         Supplier<CiFailureAnalyzer.Input> input, String category, String summary,
                         CiCodeFixService.Mode mode, List<String> goSteps) {
        boolean go = mode == CiCodeFixService.Mode.GO_CHECKS;
        // 취약점 건은 SBOM 수정(grype 재검사)이 맡는다 — 아티팩트가 없어 로그 분석으로 온 경우에도 여기서는 손대지 않는다.
        // Go 검사(govulncheck 포함)는 SBOM 수정이 보지 않는 go.mod·툴체인이라 분류와 상관없이 돈다.
        if ((!go && "VULNERABILITY".equals(category)) || !codeFix.wants(mode, streak.count(), target.dryRun(), target.fix())) {
            return;
        }
        CiCodeFixService.Request req = new CiCodeFixService.Request(row.getId(), target.rowChannel(), alert, run,
                streak.count(), streak.recovered(), fingerprint, target.dryRun(), target.fix(), mode, goSteps,
                target.atRunCommit() ? run.headSha() : null);
        Optional<String> placeholder = Optional.empty();
        CiCodeFixService.Result result;
        String key = null;
        try {
            key = codeFix.keyOf(req);
            result = codeFix.existing(req).orElse(null);
            if (result == null) {
                placeholder = reply(target, go
                        ? "🔧 Go 검사(" + String.join(", ", goSteps) + ") 실패를 고치는 중이에요… (Claude 에이전트가 레포를 살펴봐요, 길면 30분)"
                        : "🔧 " + (streak.count() >= 2 ? streak.count() + "번 연속 실패라 " : "")
                        + "원인을 다시 보고 수정 방법을 찾는 중이에요… (Claude 에이전트가 레포를 살펴봐요, 길면 30분)");
                result = codeFix.fix(req, input.get(), category, summary);
            }
        } catch (RuntimeException e) {
            log.warn("ci-alert code fix failed id={}: {}", row.getId(), e.toString());
            result = CiCodeFixService.Result.of(CiCodeFixService.Status.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        String text = CiCodeFixService.render(result, streak.count(), mode);
        if (placeholder.isPresent() && !target.dryRun()) {
            slack.updateMessage(target.channel(), placeholder.get(), text, null);
        } else if (!text.isBlank()) {
            reply(target, text);
        }
        // 앞선 시도를 다시 보여 준 것은 새 시도로 남기지 않는다 — 그래야 24시간 뒤에는 에이전트가 다시 돈다.
        if (!result.reused() && key != null) {
            row.recordFix(key, result.status().name(), CiCodeFixService.record(result));
            if (result.url().contains("/pull/")) {
                // SBOM 알림 행에는 SBOM 수정 PR 이 이미 있을 수 있어 뒤에 붙인다 (재사용은 마지막 링크를 쓴다)
                String prior = row.getPrUrls();
                row.recordPrs(prior == null || prior.isBlank() ? result.url() : prior + "," + result.url());
            }
            repository.save(row);
        }
    }

    // 24시간 안에 같은 지문으로 분석을 끝낸 알림이 있으면 그 분석 링크만 단다. 답글을 달았으면 true.
    private boolean replyIfRepeat(CiAlertEntity row, Target target, String repo, String fingerprint) {
        Instant since = Instant.now().minus(Duration.ofHours(props.repeatWindowHours()));
        Optional<CiAlertEntity> prior = repository
                .findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                        target.rowChannel(), repo, fingerprint, Status.DONE, since, row.getId());
        if (prior.isEmpty()) {
            return false;
        }
        CiAlertEntity p = prior.get();
        String permalink = p.getReplyTs() == null ? null
                : slack.getPermalink(p.getChannel(), p.getReplyTs()).orElse(null);
        Optional<String> ts = reply(target, CiReplyRenderer.repeat(
                KST_TIME.format(p.getCreatedAt()), permalink, p.getSummary()));
        row.finish(Status.REPEAT, fingerprint, p.getCategory(), p.getSummary(), ts.orElse(null));
        repository.save(row);
        react(target, "repeat");
        return true;
    }

    // STUDY: SBOM 알림은 고칠 때까지 main push 마다 반복된다. 지문을 "취약점 집합"으로 만들어
    //        같은 취약점이 남아 있는 동안은 첫 분석 링크만 단다 (새 취약점이 추가되면 지문이 바뀌어 다시 분석).
    private void replySbom(CiAlertEntity row, CiAlert alert, Target target, RunInfo run, GrypeReport report) {
        String repo = alert.repo();
        row.identify(repo, alert.runId(), CiAlert.Kind.SBOM.name());   // 제목이 일반 CI 알림이어도 취약점 건으로 기록
        List<String> keys = report.findings().stream().map(GrypeReport.Finding::key).sorted().toList();
        String fingerprint = LogExcerpts.fingerprint(repo, "sbom", keys, List.of());
        row.recordVulns(String.join(",", report.findings().stream()
                .map(GrypeReport.Finding::vulnId).distinct().sorted().toList()));
        // 재처리에서 수정까지 요청했으면(fix) 반복 판단을 건너뛴다 — 앞선 분석이 있어도 수정은 돌아야 하므로.
        if (!target.fix() && replyIfRepeat(row, target, repo, fingerprint)) {
            return;
        }
        boolean fix = sbomFix.enabled() && (!target.dryRun() || target.fix())
                && report.findings().stream().anyMatch(GrypeReport.Finding::fixable);
        String text = SbomReplyRenderer.render(repo, run.name(), report, img -> props.ownerOf(img, repo),
                run.headSha(), alert.runUrl(), sbomFix.enabled());
        Optional<String> ts = reply(target, text);
        row.finish(Status.DONE, fingerprint, "VULNERABILITY", SbomReplyRenderer.summary(report), ts.orElse(null));
        repository.save(row);
        if (fix) {
            runFix(row, alert, report, target);
        }
        react(target, "white_check_mark");
    }

    // STUDY: 자동 수정 (v0.0.94) — 수 분 걸리므로 "만드는 중" 답글을 먼저 달고 끝나면 그 답글을 결과로 바꾼다.
    private void runFix(CiAlertEntity row, CiAlert alert, GrypeReport report, Target target) {
        Optional<String> placeholder = reply(target, "🔧 수정 PR 만드는 중이에요… (Claude 에이전트가 고치고 봇이 grype 로 다시 검사해요)");
        List<SbomFixService.RepoResult> results;
        try {
            results = sbomFix.fix(row.getId(), alert, report, img -> props.ownerOf(img, alert.repo()), target.dryRun());
        } catch (RuntimeException e) {
            log.warn("ci-alert fix failed id={}: {}", row.getId(), e.toString());
            results = List.of(new SbomFixService.RepoResult(alert.repo(), SbomFixService.Status.FAILED, "",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        String text = SbomFixService.render(results);
        if (placeholder.isPresent() && !target.dryRun()) {
            slack.updateMessage(target.channel(), placeholder.get(), text, null);
        } else {
            reply(target, text);
        }
        // PR 링크만 남긴다 (SUBMODULE_MISSING 의 url 은 워크플로 페이지)
        row.recordPrs(String.join(",", results.stream().map(SbomFixService.RepoResult::url).filter(u -> u.contains("/pull/")).toList()));
        repository.save(row);
    }

    static boolean sbomScanFailed(List<JobInfo> failedJobs) {
        return failedJobs.stream().anyMatch(j -> j.failedStepNames().stream().anyMatch(SBOM_SCAN_STEPS::contains));
    }

    private String downloadLog(String repo, JobInfo job) {
        try {
            return github.downloadJobLogTail(repo, job.id(), LOG_TAIL_BYTES);
        } catch (GitHubActionsException e) {
            log.warn("ci-alert log download failed repo={} job={}: {}", repo, job.id(), e.getMessage());
            return "";
        }
    }

    private CommitInfo commitOrEmpty(String repo, RunInfo run) {
        try {
            return github.getCommit(repo, run.headSha());
        } catch (GitHubActionsException e) {
            log.warn("ci-alert commit lookup failed repo={} sha={}: {}", repo, run.headSha(), e.getMessage());
            return new CommitInfo(run.headSha(), "", "", List.of());
        }
    }

    // 같은 워크플로의 연속 실패 횟수 (이번 run 포함).
    // closed: 그 앞에서 성공한 실행을 찾았다 (아니면 "N번 이상"). recovered: 이번 run 뒤의 실행이 성공했다.
    record RunStreak(int count, boolean closed, boolean known, boolean recovered) {
        static final RunStreak UNKNOWN = new RunStreak(1, false, false, false);

        // "3번 연속 실패" / "직전 실행은 성공" / "2번 이상 연속 실패" / "" (알 수 없음)
        String label() {
            if (!known) {
                return "";
            }
            if (closed) {
                return count == 1 ? "직전 실행은 성공" : count + "번 연속 실패";
            }
            return count >= 2 ? count + "번 이상 연속 실패" : "";
        }
    }

    String runStatus(String repo, RunInfo run) {
        return runStreak(repo, run).label();
    }

    // 같은 워크플로의 최근 run 을 이번 run 기준으로 거슬러 센다. 취소·건너뜀은 판단에서 빼고 넘어간다.
    RunStreak runStreak(String repo, RunInfo run) {
        List<RunSummary> recent;
        try {
            recent = github.recentRuns(repo, run.workflowId(), run.headBranch(), 10);
        } catch (GitHubActionsException e) {
            return RunStreak.UNKNOWN;
        }
        // recent 는 최신순. run id 는 생성 순으로 커지므로 이번 run 보다 작은 id 부터가 "이전 실행"이다.
        int start = recent.size();
        boolean recovered = false;
        boolean newerSeen = false;
        for (int i = 0; i < recent.size(); i++) {
            long id = recent.get(i).id();
            if (id == run.id()) {
                start = i + 1;
                break;
            }
            if (id < run.id()) {
                start = i;
                break;
            }
            // 이번 run 보다 나중 실행 중 결론이 난 가장 최근 것이 성공이면 이미 풀린 실패다.
            String c = recent.get(i).conclusion();
            if (!newerSeen && ("success".equals(c) || "failure".equals(c) || "timed_out".equals(c))) {
                newerSeen = true;
                recovered = "success".equals(c);
            }
        }
        int streak = 1;
        for (int i = start; i < recent.size(); i++) {
            String c = recent.get(i).conclusion();
            if ("failure".equals(c) || "timed_out".equals(c)) {
                streak++;
            } else if ("success".equals(c)) {
                return new RunStreak(streak, true, true, recovered);
            }
        }
        return new RunStreak(streak, false, true, recovered);
    }

    private void fail(CiAlertEntity row, Target target, CiAlert alert, String reason) {
        reply(target, CiReplyRenderer.failure(reason, alert.runUrl()));
        row.fail(Status.FAILED, reason);
        repository.save(row);
        react(target, "warning");
    }

    private static String githubReason(GitHubActionsException e) {
        return switch (e.status()) {
            case 401, 403 -> "GitHub 권한이 없어요(" + e.status() + ") — GITHUB_ALERT_TOKEN 권한을 확인해주세요";
            case 404 -> "GitHub 에서 run 을 찾을 수 없어요(404)";
            default -> "GitHub 조회 실패 (" + e.getMessage() + ")";
        };
    }

    private Optional<String> reply(Target t, String text) {
        if (t.dryRun()) {
            String origin = t.alertTs() == null ? "run 링크로 직접 실행"
                    : slack.getPermalink(t.channel(), t.alertTs()).map(p -> "<" + p + "|원본 알림>")
                            .orElse("원본 알림 " + t.alertTs());
            slack.sendDirectMessage(t.dryRunUser(), "🧪 *재처리 테스트* · " + origin + "\n" + text);
            return Optional.empty();
        }
        return slack.postThreadReplyTs(t.channel(), t.alertTs(), text);
    }

    private void react(Target t, String emoji) {
        if (!t.dryRun()) {
            slack.addReaction(t.channel(), t.alertTs(), emoji);
        }
    }

    private void unreact(Target t, String emoji) {
        if (!t.dryRun()) {
            slack.removeReaction(t.channel(), t.alertTs(), emoji);
        }
    }

    private boolean isTooOld(String ts) {
        try {
            long sec = (long) Double.parseDouble(ts);
            return Instant.now().getEpochSecond() - sec > props.maxAlertAgeMinutes() * 60L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
