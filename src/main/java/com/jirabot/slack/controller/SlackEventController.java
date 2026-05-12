package com.jirabot.slack.controller;

import com.jirabot.slack.client.IntentClassifier;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.ThreadActionClassifier;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.ThreadActionResult;
import com.jirabot.slack.config.AsyncConfig;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.dto.IssueCreateCommand;
import com.jirabot.slack.dto.SlackEventEnvelope;
import com.jirabot.slack.dto.SlackEventInner;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.IntentFailureEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.BugQueryService;
import com.jirabot.slack.service.IssueCreateService;
import com.jirabot.slack.service.IssueSearchService;
import com.jirabot.slack.service.JiraSyncService;
import com.jirabot.slack.service.ScrumReportService;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 핸들러가 @Async 서비스를 호출하면 즉시 ResponseEntity.ok()로 200 반환 가능 → Slack 3초 제한 통과.
@RestController
@RequestMapping(path = "/api/slack", produces = MediaType.APPLICATION_JSON_VALUE)
public class SlackEventController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventController.class);

    // STUDY: 하이브리드 라우팅 — 키워드 매칭 먼저, 실패 시 Claude 분류로 fallback.
    //        키워드 명령은 즉시 실행(0초), Claude 분류는 비동기(~30초).
    private static final String HELP_TEXT = """
            :robot_face: *지라 사용법*

            *키워드 명령 (즉시 실행):*
              `@지라 help` — 이 도움말 표시
              `@지라 scrum` — 스프린트 일일 리포트
              `@지라 내작업` — 내 진행 중인 작업 조회
              `@지라 작업 김영현` — 특정 팀원의 작업 조회
              `@지라 등록 <Jira 사용자명>` — 내 Slack ↔ Jira 계정 연결
              `@지라 검색 <키워드>` — 이슈 제목/설명으로 검색 (예: `@지라 검색 preset`)
              `@지라 버그` — 최근 7일간 해결된 버그 조회
              `@지라 버그 2026.03.11` — 특정 날짜 이후 해결된 버그 조회
              `@지라 sync` — Jira 이슈를 로컬 DB에 동기화
              `@지라 통계` — 현재 스프린트 SP 통계 요약
              `@지라 완료` — 이슈 스레드에서 → Jira 완료 처리

            *스레드 액션 (이슈 스레드에서 댓글로 사용):*
              `@지라 하위작업 <내용>` — 하위작업 생성
              `@지라 댓글 <내용>` — Jira 코멘트 추가
              `@지라 수정 <내용>` — Jira 설명에 내용 추가
              또는 자연어로 입력하면 AI가 액션을 판단합니다.

            *자연어 입력 (AI 분류 → Jira 이슈 생성):*
              `@지라 로그인 페이지에서 500 에러 발생` → :bug: 버그로 등록
              `@지라 다크모드 지원해주세요` → :pencil: 기능 요청으로 등록

            이슈 등록 시 AI가 자동으로 분류(BUG/FEATURE/OTHER)하고 Story Point를 추정합니다.""";

    // STUDY: 날짜 파싱용 정규식. yyyy.MM.dd, yyyy-MM-dd, yyyy/MM/dd 형식을 모두 지원.
    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final IssueCreateService issueCreateService;
    private final IssueSearchService issueSearchService;
    private final ScrumReportService scrumReportService;
    private final BugQueryService bugQueryService;
    private final JiraSyncService jiraSyncService;
    private final JiraApiClient jiraApiClient;
    private final JiraProperties jiraProps;
    private final IssueRepository issueRepository;
    private final IntentClassifier intentClassifier;
    private final ThreadActionClassifier threadActionClassifier;
    private final IntentFailureRepository intentFailureRepository;
    private final UserMappingRepository userMappingRepository;
    private final SlackNotifier slackNotifier;
    private final Executor slackExecutor;
    private final SlackEventDeduplicator deduplicator;
    private final Set<String> allowedChannels;

    public SlackEventController(IssueCreateService issueCreateService,
                                IssueSearchService issueSearchService,
                                ScrumReportService scrumReportService,
                                BugQueryService bugQueryService,
                                JiraSyncService jiraSyncService,
                                JiraApiClient jiraApiClient,
                                JiraProperties jiraProps,
                                IssueRepository issueRepository,
                                IntentClassifier intentClassifier,
                                ThreadActionClassifier threadActionClassifier,
                                IntentFailureRepository intentFailureRepository,
                                UserMappingRepository userMappingRepository,
                                SlackNotifier slackNotifier,
                                @Qualifier(AsyncConfig.SLACK_EXECUTOR) Executor slackExecutor,
                                SlackEventDeduplicator deduplicator,
                                @Value("${slack.allowed-channels:}") String allowedChannelsConfig) {
        this.issueCreateService = issueCreateService;
        this.issueSearchService = issueSearchService;
        this.scrumReportService = scrumReportService;
        this.bugQueryService = bugQueryService;
        this.jiraSyncService = jiraSyncService;
        this.jiraApiClient = jiraApiClient;
        this.jiraProps = jiraProps;
        this.issueRepository = issueRepository;
        this.intentClassifier = intentClassifier;
        this.threadActionClassifier = threadActionClassifier;
        this.intentFailureRepository = intentFailureRepository;
        this.userMappingRepository = userMappingRepository;
        this.slackNotifier = slackNotifier;
        this.slackExecutor = slackExecutor;
        this.deduplicator = deduplicator;
        // STUDY: 허용 채널이 비어있으면 모든 채널 허용. 쉼표 구분으로 파싱.
        if (allowedChannelsConfig == null || allowedChannelsConfig.isBlank()) {
            this.allowedChannels = Set.of();
        } else {
            this.allowedChannels = Set.of(allowedChannelsConfig.split(","));
        }
        log.info("Allowed channels: {}", this.allowedChannels.isEmpty() ? "ALL" : this.allowedChannels);
    }

    private boolean isChannelAllowed(String channel) {
        return allowedChannels.isEmpty() || allowedChannels.contains(channel);
    }

    // STUDY: Slack app_mention 이벤트의 text 는 "<@U0AT5U95C4T> 버그 내용" 형태.
    //        멘션 태그를 제거해야 Claude 가 순수 내용만 분류할 수 있다.
    private static final java.util.regex.Pattern MENTION_PATTERN =
            java.util.regex.Pattern.compile("<@[A-Z0-9]+>\\s*");

    static String stripMention(String text) {
        if (text == null) return "";
        return MENTION_PATTERN.matcher(text).replaceAll("").strip();
    }

    // STUDY: 하이브리드 라우팅.
    //        1. 키워드 매칭 → 즉시 실행
    //        2. 스레드 댓글 + 부모 이슈 있음 → 스레드 액션 모드
    //        3. 그 외 → Haiku 의도 분류
    private void routeCommand(SlackEventInner event, String cleaned) {
        String lower = cleaned.toLowerCase();

        // 1차: 키워드 매칭 (스레드 안밖 모두 동작)
        switch (lower) {
            case "help", "도움말" -> { handleHelp(event); return; }
            case "scrum", "스크럼" -> { handleScrum(event); return; }
            case "내작업", "my" -> { handleMyWork(event); return; }
            case "sync", "동기화" -> { handleSync(event); return; }
            case "완료", "done" -> { handleComplete(event); return; }
            case "버그", "버그조회", "bug" -> {
                // 날짜 없음 → 최근 7일
                handleBugQuery(event, LocalDate.now(KST).minusDays(7));
                return;
            }
            case "통계", "stats", "statistics" -> { handleStatistics(event); return; }
        }
        // STUDY: "버그 2026.03.11" 패턴 — 버그/bug 뒤에 날짜가 오면 해결된 버그 조회.
        //        "버그 발생했어요" 같은 서술문은 날짜가 아니므로 Haiku로 fall through.
        if ((lower.startsWith("버그 ") || lower.startsWith("bug ")) && cleaned.length() > 2) {
            String afterKeyword = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            Matcher dateMatcher = DATE_PATTERN.matcher(afterKeyword);
            if (dateMatcher.matches()) {
                try {
                    LocalDate date = LocalDate.of(
                            Integer.parseInt(dateMatcher.group(1)),
                            Integer.parseInt(dateMatcher.group(2)),
                            Integer.parseInt(dateMatcher.group(3)));
                    handleBugQuery(event, date);
                } catch (DateTimeException e) {
                    // STUDY: 2026.13.40 같은 유효하지 않은 날짜 → 기본 7일 + 경고 메시지
                    replyThread(event, ":warning: 날짜 형식이 올바르지 않아 최근 7일로 조회합니다.");
                    handleBugQuery(event, LocalDate.now(KST).minusDays(7));
                }
                return;
            }
            // 날짜가 아닌 서술문 → Haiku fallback으로 넘김 (return 하지 않음)
        }
        if (lower.startsWith("작업 ") && cleaned.length() > 3) {
            handleMemberWork(event, cleaned.substring(3).strip());
            return;
        }
        if (lower.startsWith("등록 ") || lower.startsWith("register ")) {
            String jiraUsername = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            handleRegisterUser(event, jiraUsername);
            return;
        }
        if (lower.equals("검색") || lower.equals("search")) {
            replyThread(event, ":mag: 검색어를 입력해주세요. 예: `@지라 검색 로그인`");
            return;
        }
        if (lower.startsWith("검색 ") || lower.startsWith("search ")) {
            String keyword = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            issueSearchService.searchByKeyword(keyword)
                    .thenAccept(result -> replyThread(event, result))
                    .exceptionally(ex -> {
                        log.warn("Keyword search failed for keyword='{}': {}", keyword, ex.toString());
                        replyThread(event, ":x: 검색 중 오류가 발생했어요.");
                        return null;
                    });
            return;
        }

        // 2차: 스레드 댓글이면 부모 이슈 확인 → 스레드 액션 모드
        if (event.thread_ts() != null) {
            Optional<IssueEntity> parentIssue = issueRepository
                    .findBySlackChannelAndSlackThreadTs(event.channel(), event.thread_ts());
            if (parentIssue.isPresent()) {
                handleThreadAction(event, cleaned, parentIssue.get());
                return;
            }
        }

        // 3차: Haiku 의도 분류 → 새 이슈 생성 등
        handleWithIntent(event, cleaned);
    }

    // STUDY: 스레드 액션 — 부모 이슈가 있는 스레드에서 댓글로 @지라 호출 시.
    //        키워드 우선 매칭 → Haiku 분류 fallback → 각 액션 실행.
    private void handleThreadAction(SlackEventInner event, String cleaned, IssueEntity parentIssue) {
        String lower = cleaned.toLowerCase();
        String threadTs = event.thread_ts();

        // 스레드 키워드 매칭
        if (lower.startsWith("하위작업 ") || lower.startsWith("subtask ")) {
            String content = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            executeSubTask(event, parentIssue, content);
            return;
        }
        if (lower.startsWith("댓글 ") || lower.startsWith("comment ")) {
            String content = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            executeComment(event, parentIssue, content);
            return;
        }
        if (lower.startsWith("수정 ") || lower.startsWith("modify ")) {
            String content = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            executeModify(event, parentIssue, content);
            return;
        }

        // Haiku 스레드 액션 분류
        slackExecutor.execute(() -> {
            log.info("Thread action classification for issue={} input='{}'", parentIssue.getIssueKey(), cleaned);

            List<String> threadMessages = slackNotifier.getThreadMessages(event.channel(), threadTs);
            ThreadActionResult action = threadActionClassifier.classify(parentIssue, threadMessages, cleaned);
            log.info("Thread action classified: action={} confidence={}", action.action(), action.confidence());

            if (!action.isActionable()) {
                intentFailureRepository.save(new IntentFailureEntity(
                        cleaned, "UNKNOWN_THREAD_ACTION",
                        String.format("action=%s, confidence=%.2f, parent=%s",
                                action.action(), action.confidence(), parentIssue.getIssueKey()),
                        event.user(), event.channel()));
                replyInThread(event, threadTs,
                        ":thinking_face: 이해하지 못했어요. 스레드에서 `하위작업`, `댓글`, `수정`, `완료` 를 사용해보세요.");
                return;
            }

            String content = action.extracted() != null ? action.extracted().getOrDefault("content", cleaned) : cleaned;
            switch (action.action()) {
                case "sub_task" -> executeSubTask(event, parentIssue, content);
                case "comment" -> executeComment(event, parentIssue, content);
                case "modify" -> executeModify(event, parentIssue, content);
                case "complete" -> handleComplete(event);
                default -> replyInThread(event, threadTs,
                        ":thinking_face: 이해하지 못했어요.");
            }
        });
    }

    private void executeSubTask(SlackEventInner event, IssueEntity parentIssue, String content) {
        slackExecutor.execute(() -> {
            try {
                // STUDY: Haiku 분류 → Sonnet 상세화(제목/SP) → Jira 하위작업 생성
                var intentHint = new IntentResult("register_story", 0.9, Map.of("keyword", content), content);
                var classification = issueCreateService.classifyOnly(content, intentHint);

                String subKey = jiraApiClient.createSubTask(
                        parentIssue.getIssueKey(), classification.title(), classification.storyPoint());
                replyInThread(event, event.thread_ts(), String.format(
                        ":white_check_mark: 하위작업 생성: *%s* %s (SP %d)\n상위: %s",
                        subKey, classification.title(), classification.storyPoint(), parentIssue.getIssueKey()));
            } catch (Exception e) {
                log.error("Sub-task creation failed: {}", e.toString());
                replyInThread(event, event.thread_ts(),
                        ":x: 하위작업 생성에 실패했습니다: " + e.getMessage());
            }
        });
    }

    private void executeComment(SlackEventInner event, IssueEntity parentIssue, String content) {
        slackExecutor.execute(() -> {
            try {
                jiraApiClient.addComment(parentIssue.getIssueKey(), content);
                replyInThread(event, event.thread_ts(), String.format(
                        ":speech_balloon: *%s*에 코멘트가 추가되었습니다.", parentIssue.getIssueKey()));
            } catch (Exception e) {
                log.error("Comment failed: {}", e.toString());
                replyInThread(event, event.thread_ts(),
                        ":x: 코멘트 추가에 실패했습니다: " + e.getMessage());
            }
        });
    }

    private void executeModify(SlackEventInner event, IssueEntity parentIssue, String content) {
        slackExecutor.execute(() -> {
            try {
                jiraApiClient.appendDescription(parentIssue.getIssueKey(), content);
                replyInThread(event, event.thread_ts(), String.format(
                        ":pencil2: *%s* 설명이 업데이트되었습니다.", parentIssue.getIssueKey()));
            } catch (Exception e) {
                log.error("Description update failed: {}", e.toString());
                replyInThread(event, event.thread_ts(),
                        ":x: 설명 수정에 실패했습니다: " + e.getMessage());
            }
        });
    }

    private void replyInThread(SlackEventInner event, String threadTs, String message) {
        if (event.channel() != null && threadTs != null) {
            slackNotifier.postThreadReply(event.channel(), threadTs, message);
        }
    }

    // STUDY: 키워드 매칭 실패 시 Haiku로 1차 의도 분류 → intent별 후속 처리.
    //        Haiku 실패/unknown 시 Sonnet 호출하지 않고 안내 메시지만 반환.
    private void handleWithIntent(SlackEventInner event, String cleaned) {
        slackExecutor.execute(() -> {
            IntentResult intent = intentClassifier.classify(cleaned);
            log.info("Haiku classified: intent={} confidence={} input='{}'",
                    intent.intent(), intent.confidence(), cleaned);

            if (!intent.isActionable()) {
                String errorType = intent.confidence() < IntentResult.CONFIDENCE_THRESHOLD
                        ? "LOW_CONFIDENCE" : "UNKNOWN_INTENT";
                String errorDetail = String.format("intent=%s, confidence=%.2f",
                        intent.intent(), intent.confidence());
                intentFailureRepository.save(new IntentFailureEntity(
                        cleaned, errorType, errorDetail, event.user(), event.channel()));
                replyThread(event, ":thinking_face: 이해하지 못했어요. `@지라 help`로 사용 가능한 명령을 확인해주세요.");
                return;
            }

            switch (intent.intent()) {
                case "register_bug", "register_story" ->
                        issueCreateService.createFromSlackText(
                                IssueCreateCommand.from(event, cleaned), intent);
                case "search" -> {
                    // STUDY: Haiku가 검색 의도로 분류한 경우, Sonnet 기반 의미 검색을 수행한다.
                    //        서비스 레이어에서 비동기 처리되므로 executor 중첩 없음.
                    String fallbackKeyword = intent.extracted() != null
                            ? intent.extracted().getOrDefault("keyword", cleaned) : cleaned;
                    issueSearchService.searchSemantic(cleaned, fallbackKeyword)
                            .thenAccept(result -> replyThread(event, result))
                            .exceptionally(ex -> {
                                log.warn("Semantic search failed: {}", ex.toString());
                                replyThread(event, ":x: 검색 중 오류가 발생했어요.");
                                return null;
                            });
                }
                case "statistics" ->
                        handleStatistics(event);
                case "my_tasks" ->
                        handleMyWork(event);
                case "skip" ->
                        replyThread(event, ":no_entry_sign: 구체적인 내용을 포함해주세요.\n" +
                                "예: `@지라 로그인 페이지에서 500 에러 발생`");
                default ->
                        replyThread(event, ":thinking_face: 이해하지 못했어요. `@지라 help`로 사용 가능한 명령을 확인해주세요.");
            }
        });
    }

    private void replyThread(SlackEventInner event, String message) {
        if (event.channel() != null && event.ts() != null) {
            slackNotifier.postThreadReply(event.channel(), event.ts(), message);
        }
    }

    // STUDY: 서비스로 분리된 버그 조회. 날짜 파싱은 routeCommand()에서 수행하고 LocalDate만 전달.
    private void handleBugQuery(SlackEventInner event, LocalDate sinceDate) {
        bugQueryService.queryResolvedBugs(sinceDate)
                .thenAccept(result -> replyThread(event, result))
                .exceptionally(ex -> {
                    log.warn("Bug query failed: {}", ex.toString());
                    replyThread(event, ":x: 버그 조회 중 오류가 발생했어요.");
                    return null;
                });
    }

    private void handleHelp(SlackEventInner event) {
        if (event.channel() != null && event.ts() != null) {
            slackNotifier.postThreadReply(event.channel(), event.ts(), HELP_TEXT);
        }
    }

    private void handleScrum(SlackEventInner event) {
        log.info("Scrum report requested by user={}", event.user());
        scrumReportService.generateReport()
                .thenAccept(report -> {
                    if (event.channel() != null) {
                        slackNotifier.postMessage(event.channel(), report);
                    }
                })
                .exceptionally(ex -> {
                    log.warn("Scrum report failed: {}", ex.toString());
                    replyThread(event, ":x: 스크럼 리포트 생성 중 오류가 발생했어요.");
                    return null;
                });
    }

    private void handleMyWork(SlackEventInner event) {
        log.info("My work requested by user={}", event.user());
        scrumReportService.generateMyReport(event.user())
                .thenAccept(report -> {
                    if (event.channel() != null && event.ts() != null) {
                        slackNotifier.postThreadReply(event.channel(), event.ts(), report);
                    }
                })
                .exceptionally(ex -> {
                    log.warn("My-work report failed for user={}: {}", event.user(), ex.toString());
                    replyThread(event, ":x: 내 작업 조회 중 오류가 발생했어요.");
                    return null;
                });
    }

    private void handleSync(SlackEventInner event) {
        log.info("Jira sync requested by user={}", event.user());
        // STUDY: 동기화는 동기 실행 후 결과를 스레드에 알린다.
        //        @Async가 아닌 이유: 결과 메시지를 바로 받아야 하므로.
        //        다만 Slack 3초 ack는 이미 200을 반환했으므로 블로킹해도 무방.
        slackExecutor.execute(() -> {
            String result = jiraSyncService.syncActiveSprint();
            if (event.channel() != null && event.ts() != null) {
                slackNotifier.postThreadReply(event.channel(), event.ts(), result);
            }
        });
    }

    private void handleComplete(SlackEventInner event) {
        // STUDY: thread_ts가 있으면 스레드 내 댓글. thread_ts로 DB에서 이슈를 찾아 완료 처리.
        if (event.thread_ts() == null) {
            if (event.channel() != null && event.ts() != null) {
                slackNotifier.postThreadReply(event.channel(), event.ts(),
                        "이슈 생성 스레드에서 댓글로 `@지라 완료`를 사용해주세요.");
            }
            return;
        }

        log.info("Complete requested in thread={} by user={}", event.thread_ts(), event.user());
        slackExecutor.execute(() -> {
            Optional<IssueEntity> found = issueRepository
                    .findBySlackChannelAndSlackThreadTs(event.channel(), event.thread_ts());
            if (found.isEmpty()) {
                slackNotifier.postThreadReply(event.channel(), event.thread_ts(),
                        "이 스레드에서 생성된 이슈를 찾을 수 없습니다.");
                return;
            }

            IssueEntity issue = found.get();
            if (StatusCategory.DONE.equals(issue.getStatusCategory())) {
                slackNotifier.postThreadReply(event.channel(), event.thread_ts(),
                        String.format("*%s*은 이미 완료 상태입니다.", issue.getIssueKey()));
                return;
            }

            boolean success = jiraApiClient.transitionIssue(issue.getIssueKey(), StatusCategory.DONE);
            if (success) {
                issue.updateFrom(issue.getSummary(), issue.getIssueType(),
                        StatusCategory.DONE, StatusCategory.DONE,
                        issue.getAssignee(), issue.getStoryPoint(), java.time.Instant.now());
                issueRepository.save(issue);
                slackNotifier.postThreadReply(event.channel(), event.thread_ts(),
                        String.format(":white_check_mark: *%s* %s → 완료 처리되었습니다.",
                                issue.getIssueKey(), issue.getSummary()));
            } else {
                slackNotifier.postThreadReply(event.channel(), event.thread_ts(),
                        String.format("*%s* 완료 처리에 실패했습니다. Jira에서 직접 확인해주세요.",
                                issue.getIssueKey()));
            }
        });
    }

    private void handleRegisterUser(SlackEventInner event, String jiraUsername) {
        log.info("User registration requested: slackUser={} jiraUsername={}", event.user(), jiraUsername);
        new Thread(() -> {
            // 1. Jira에서 유저 검색
            String accountId = jiraApiClient.findAccountId(jiraUsername);
            if (accountId == null) {
                replyThread(event, String.format(
                        ":x: Jira에서 *%s* 사용자를 찾을 수 없습니다.\nJira에 등록된 이름으로 다시 시도해주세요.",
                        jiraUsername));
                return;
            }

            // 2. Slack 실명 조회
            String slackName = slackNotifier.getUserRealName(event.user());
            if (slackName == null) slackName = event.user();

            // 3. DB 매핑 저장 (있으면 업데이트, 없으면 생성)
            var existing = userMappingRepository.findBySlackUserId(event.user());
            if (existing.isPresent()) {
                var entity = existing.get();
                entity.setJiraDisplayName(jiraUsername);
                entity.setJiraAccountId(accountId);
                entity.setSlackDisplayName(slackName);
                userMappingRepository.save(entity);
            } else {
                userMappingRepository.save(new com.jirabot.slack.entity.UserMappingEntity(
                        event.user(), slackName, jiraUsername, accountId));
            }

            replyThread(event, String.format(
                    ":white_check_mark: 등록 완료!\nSlack: *%s*\nJira: *%s*\n\n앞으로 이슈 생성 시 보고자/담당자가 자동으로 설정됩니다.",
                    slackName, jiraUsername));
        }).start();
    }

    private void handleMemberWork(SlackEventInner event, String memberName) {
        log.info("Member work requested for name={} by user={}", memberName, event.user());
        scrumReportService.generateMemberReport(memberName)
                .thenAccept(report -> {
                    if (event.channel() != null && event.ts() != null) {
                        slackNotifier.postThreadReply(event.channel(), event.ts(), report);
                    }
                })
                .exceptionally(ex -> {
                    log.warn("Member-work report failed for name={}: {}", memberName, ex.toString());
                    replyThread(event, ":x: 작업 조회 중 오류가 발생했어요.");
                    return null;
                });
    }

    // STUDY: 다른 핸들러와 동일하게 replyThread()로 스레드 응답. 채널에 긴 리포트가 올라가면 대화 흐름 방해.
    private void handleStatistics(SlackEventInner event) {
        log.info("Statistics report requested by user={}", event.user());
        scrumReportService.generateStatisticsReport()
                .thenAccept(report -> replyThread(event, report))
                .exceptionally(ex -> {
                    log.warn("Statistics report failed: {}", ex.toString());
                    replyThread(event, ":x: 통계 리포트 생성 중 오류가 발생했어요.");
                    return null;
                });
    }

    @PostMapping(path = "/event", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> onEvent(@RequestBody SlackEventEnvelope envelope) {
        if (envelope == null) {
            return ResponseEntity.ok(Map.of("ok", true));
        }

        if (SlackEventEnvelope.URL_VERIFICATION.equals(envelope.type())) {
            return ResponseEntity.ok(Map.of("challenge",
                    envelope.challenge() == null ? "" : envelope.challenge()));
        }

        if (SlackEventEnvelope.EVENT_CALLBACK.equals(envelope.type()) && envelope.event() != null) {
            SlackEventInner event = envelope.event();
            // STUDY: app_mention 이벤트만 처리 — 일반 message 이벤트는 무시하여
            //        @봇멘션 없는 일반 대화가 Jira 이슈로 생성되지 않도록 한다.
            if (!"app_mention".equals(event.type())) {
                log.debug("Ignoring non-mention event type={}", event.type());
                return ResponseEntity.ok(Map.of("ok", true));
            }
            if (!isChannelAllowed(event.channel())) {
                log.debug("Ignoring event from non-allowed channel={}", event.channel());
                return ResponseEntity.ok(Map.of("ok", true));
            }
            if (deduplicator.isDuplicate(event.channel(), event.ts())) {
                return ResponseEntity.ok(Map.of("ok", true));
            }
            String cleaned = stripMention(event.text());
            if (event.isFromHuman() && !cleaned.isBlank()) {
                routeCommand(event, cleaned.strip());
            } else {
                log.debug("Ignoring non-human or empty slack event subtype={} botId={}",
                        event.subtype(), event.bot_id());
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
