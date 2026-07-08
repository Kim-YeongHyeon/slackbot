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
import com.jirabot.slack.service.BugNotionService;
import com.jirabot.slack.service.BugQueryService;
import com.jirabot.slack.service.IssueCreateService;
import com.jirabot.slack.service.PrImportService;
import com.jirabot.slack.service.IssueSearchService;
import com.jirabot.slack.service.JiraSyncService;
import com.jirabot.slack.service.ReminderSubscriptionService;
import com.jirabot.slack.service.ScrumReportService;
import com.jirabot.slack.util.IssueCommandParser;
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

            :speech_balloon: *명령어를 외울 필요 없어요!* 평소 말하듯 자연어로 입력하면 AI가 알아서 처리해요.
              예) `@지라 등록된 버그 이슈 알려줘` · `@지라 내가 지금 뭐 하고 있지?` · `@지라 로그인하면 500 에러 나`
              이슈 등록·조회·검색·스크럼·통계·동기화·완료 등 대부분의 작업을 자연어로 할 수 있어요.
              아래 키워드 명령은 자주 쓰는 작업의 단축어입니다.

            *키워드 명령 (즉시 실행):*
              `@지라 help` — 이 도움말 표시
              `@지라 안녕` — 인사 + 사용법 안내
              `@지라 scrum` — 스프린트 일일 리포트
              `@지라 내작업` — 내 진행 중인 작업 조회
              `@지라 작업 김영현` — 특정 팀원의 작업 조회
              `@지라 등록 <Jira 사용자명>` — 내 Slack ↔ Jira 계정 연결
              `@지라 검색 <키워드>` — 이슈 제목/설명으로 검색 (예: `@지라 검색 preset`)
              `@지라 ES2-123` — 이슈 키로 상세 카드 조회 (상태 전환 버튼 포함)
              `@지라 할당 ES2-123 홍길동` — 이슈 담당자 지정 (@멘션도 가능)
              `@지라 하위작업 ES2-123 <내용>` — 특정 이슈 아래 하위작업 생성 (스레드 밖에서도 키/이름으로 지정 가능)
              `@지라 ES2-1이 ES2-2에 막혀있어` — 이슈 링크 생성 (blocks/relates/duplicate, 방향 모호 시 확인 버튼)
              `@지라 ES2-123 SP 3으로 변경` — SP/제목/마감일/우선순위 수정 (제목은 따옴표 필요)
              `@지라 ES2-123 스프린트로 옮겨줘` — 현재 활성 스프린트로 이동
              `@지라 ES2-123 링크 보여줘` — 이슈 링크 목록 조회
              `@지라 ES2-1 ES2-2 링크 해제` — 두 이슈 사이 링크 제거
              `@지라 할당알림 on` / `off` / `상태` — 이슈가 나에게 할당되면 DM 알림 (기본 ON)
              `@지라 리마인더 on` / `off` / `상태` — 평일 09:00 미해결 이슈 DM 알림 토글
              `@지라 notion백필` — Jira 전체 버그를 Notion '버그 현황' DB로 동기화
              `@지라 pr <PR URL>` (또는 PR 링크가 포함된 문장) — PR 내용을 분석해 티켓 생성·기간 기반 SP·현재 스프린트로 이동. 상태별: merged→완료, open→검토 중, draft→진행 중
              `@지라 버그` — 최근 7일간 해결된 버그 조회
              `@지라 버그 2026.03.11` — 특정 날짜 이후 해결된 버그 조회
              `@지라 sync` — Jira 이슈를 로컬 DB에 동기화
              `@지라 통계` — 현재 스프린트 SP 통계 요약
              `@지라 완료` — 이슈 스레드에서 → Jira 완료 처리

            *스레드 액션 (이슈 스레드에서 댓글로 사용):*
              `@지라 하위작업 <내용>` — 하위작업 생성
              `@지라 댓글 <내용>` — Jira 코멘트 추가
              `@지라 수정 <내용>` — Jira 설명에 내용 추가
              `@지라 담당자 <이름>` — 이 이슈의 담당자 지정
              또는 자연어로 입력하면 AI가 액션을 판단합니다.

            *자연어 입력 (AI 분류 → Jira 이슈 생성):*
              `@지라 로그인 페이지에서 500 에러 발생` → :bug: 버그로 등록
              `@지라 다크모드 지원해주세요` → :pencil: 기능 요청으로 등록

            *에픽 생성 (`에픽`/`epic` 키워드 포함 시):*
              `@지라 에픽 GCP marketplace 배포 확장` → :bookmark_tabs: 에픽으로 등록
              키워드가 들어가면 AI 분류 없이 항상 에픽으로 생성됩니다 (Story Point 없음).

            이슈 등록 시 AI가 자동으로 분류(BUG/FEATURE/OTHER)하고 Story Point를 추정합니다.""";

    // STUDY: 서버 재시작 시 Slack이 밀린 이벤트를 재전송하면 오래된 요청이 중복 처리된다.
    //        이벤트 ts(Unix epoch)가 현재 시각보다 일정 시간 이상 지났으면 stale로 간주하여 처리하지 않는다.
    private static final long STALE_EVENT_SECONDS = 180; // 3분

    // STUDY: 날짜 파싱용 정규식. yyyy.MM.dd, yyyy-MM-dd, yyyy/MM/dd 형식을 모두 지원.
    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // STUDY: 메시지 어디에 있든(자연어/unfurl <url|label>) GitHub PR URL 을 잡는다. owner/repo 세그먼트는
    //        '/', 공백, '|', '<', '>' 를 제외해 unfurl 마크업 안에서도 정확히 끊긴다.
    private static final java.util.regex.Pattern GITHUB_PR_URL =
            java.util.regex.Pattern.compile("https?://github\\.com/[^/\\s|<>]+/[^/\\s|<>]+/pull/\\d+");

    // STUDY: 순수 인사말만 매칭(인사 + 선택적 문장부호/ㅋㅋㅎㅎ). "안녕 못하는 버그" 처럼 뒤에 내용이 붙으면
    //        매칭되지 않아 이슈 생성 흐름으로 넘어간다. 인사면 가볍게 받아주고 사용법을 안내한다.
    private static final java.util.regex.Pattern GREETING_PATTERN = java.util.regex.Pattern.compile(
            "(?i)^(안녕(하세요|하십니까)?|안뇽|하이|하잉|헬로|헬루|반가워(요)?|반갑(습니다|네요)|"
                    + "hi|hello|hey|yo|howdy|gm|good\\s*morning|좋은\\s*아침|굿모닝)[\\s!.~?ㅎㅋ’'^_-]*$");

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
    private final ReminderSubscriptionService reminderSubscriptionService;
    private final BugNotionService bugNotionService;
    private final PrImportService prImportService;
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
                                ReminderSubscriptionService reminderSubscriptionService,
                                BugNotionService bugNotionService,
                                PrImportService prImportService,
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
        this.reminderSubscriptionService = reminderSubscriptionService;
        this.bugNotionService = bugNotionService;
        this.prImportService = prImportService;
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

    // STUDY: Slack ts는 "1716012345.123456" (Unix epoch 초.마이크로초) 형식.
    //        소수점 앞부분을 epoch seconds로 파싱하여 현재 시각과 비교한다.
    private boolean isStaleEvent(String ts) {
        if (ts == null || ts.isBlank()) return false;
        try {
            long eventEpoch = Long.parseLong(ts.contains(".") ? ts.substring(0, ts.indexOf('.')) : ts);
            long nowEpoch = java.time.Instant.now().getEpochSecond();
            return (nowEpoch - eventEpoch) > STALE_EVENT_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // STUDY: Slack app_mention 이벤트의 text 는 "<@U0AT5U95C4T> 버그 내용" 형태.
    //        선두(봇 호출) 멘션만 제거한다 — 본문 속 멘션은 내용이다.
    //        (예: `할당 ES2-1 <@U5>` 의 할당 대상 멘션을 전부 제거하면 명령이 깨진다.)
    private static final java.util.regex.Pattern MENTION_PATTERN =
            java.util.regex.Pattern.compile("^\\s*<@[A-Z0-9]+>\\s*");

    static String stripMention(String text) {
        if (text == null) return "";
        return MENTION_PATTERN.matcher(text).replaceFirst("").strip();
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

        // STUDY: 순수 인사말이면 가볍게 받아주고 사용법을 안내. 뒤에 내용이 붙은 문장은 매칭 안 돼 이슈 생성으로 넘어간다.
        if (GREETING_PATTERN.matcher(cleaned.strip()).matches()) {
            handleGreeting(event);
            return;
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
        if (lower.equals("리마인더") || lower.equals("reminder")) {
            replyThread(event, ":warning: 사용법: `@지라 리마인더 on` / `off` / `상태`");
            return;
        }
        if (lower.startsWith("리마인더 ") || lower.startsWith("reminder ")) {
            String arg = cleaned.substring(cleaned.indexOf(' ') + 1).strip().toLowerCase();
            handleReminder(event, arg);
            return;
        }
        if (lower.equals("notion백필") || lower.equals("notion sync") || lower.equals("노션백필")) {
            handleNotionBackfill(event);
            return;
        }
        // STUDY: 완료된 PR 등록. `@지라 pr <url>` 뿐 아니라 "이 PR(<url>) 관련 티켓 만들어줘" 같은 자연어에
        //        PR URL 이 섞여 와도 PR-import 로 보낸다(아니면 문장 전체가 이슈 제목이 되는 오작동). URL 추출은
        //        handlePrImport 가 정규식으로 처리(unfurl <url|label> 포함).
        if (GITHUB_PR_URL.matcher(cleaned).find()
                || (lower.startsWith("pr ") && cleaned.length() > 3)) {
            handlePrImport(event, cleaned);
            return;
        }
        // STUDY: 담당자 지정 — 명시적 `할당 <KEY> <이름|@멘션>` 은 스레드 안에서도 키워드 1차에서 잡혀
        //        스레드 root 이슈가 아닌 지정한 이슈에 적용된다 (스레드에 하위작업 논의가 섞여도 모호성 없음).
        if (lower.startsWith("할당 ") || lower.startsWith("assign ")) {
            handleAssign(event, cleaned.substring(cleaned.indexOf(' ') + 1).strip());
            return;
        }
        if (lower.equals("할당알림") || lower.equals("assign-dm")) {
            replyThread(event, ":warning: 사용법: `@지라 할당알림 on` / `off` / `상태`");
            return;
        }
        if (lower.startsWith("할당알림 ") || lower.startsWith("assign-dm ")) {
            handleAssignDm(event, cleaned.substring(cleaned.indexOf(' ') + 1).strip().toLowerCase());
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

        // 1.4차: 이슈 키 조회 카드 — `ES2-123` 단독(또는 조회/이슈 + 짧은 접미어)이면 상세 카드.
        //        키를 언급만 하는 서술문("ES2-123 때문에 빌드가 깨져요")은 null 이 반환돼 기존 흐름 유지.
        String cardKey = extractCardIssueKey(cleaned);
        if (cardKey != null) {
            handleIssueCard(event, cardKey);
            return;
        }

        // 1.43차: 단일 이슈 필드 수정(SP/제목/마감일/우선순위) 및 스프린트 이동. 키 1개 + 해당 키워드일 때.
        Optional<IssueCommandParser.UpdateCommand> updateCmd = IssueCommandParser.parseUpdate(cleaned);
        if (updateCmd.isPresent()) {
            handleUpdateCommand(event, updateCmd.get());
            return;
        }
        Optional<String> sprintMoveKey = IssueCommandParser.parseSprintMove(cleaned);
        if (sprintMoveKey.isPresent()) {
            handleSprintMove(event, sprintMoveKey.get());
            return;
        }

        // 1.44차: 이슈 링크. 해제(2키+해제+링크) → 조회(1키+링크+조회) → 생성(2키+관계동사) 순.
        Optional<String[]> unlinkKeys = IssueCommandParser.parseUnlink(cleaned);
        if (unlinkKeys.isPresent()) {
            handleUnlink(event, unlinkKeys.get()[0], unlinkKeys.get()[1]);
            return;
        }
        Optional<String> linkListKey = IssueCommandParser.parseLinkList(cleaned);
        if (linkListKey.isPresent()) {
            handleLinkList(event, linkListKey.get());
            return;
        }
        Optional<IssueCommandParser.LinkCommand> linkCmd = IssueCommandParser.parseLink(cleaned);
        if (linkCmd.isPresent()) {
            handleLinkCommand(event, linkCmd.get());
            return;
        }

        // 1.45차: 특정 부모 이슈 아래 하위작업 생성 (키/이름으로 지정, 스레드 밖에서도 동작).
        //        "ES2-123에 하위작업으로 'X' 추가" / "하위작업 ES2-123 X" / "<이름> 스토리 아래 하위작업 X".
        //        에픽 키워드 체크(1.5차)보다 먼저 실행 — "에픽 아래 하위작업"을 에픽 생성으로 오인하지 않도록.
        Optional<IssueCommandParser.SubtaskCommand> subtaskCmd = IssueCommandParser.parseSubtask(cleaned);
        if (subtaskCmd.isPresent()) {
            handleSubtaskCommand(event, subtaskCmd.get());
            return;
        }

        // 1.5차: 에픽 키워드 트리거 — `에픽`/`epic` 이 단어로 포함되면 AI 분류를 거치지 않고
        //        결정적으로 에픽 생성. 스토리/버그(Haiku/Sonnet 분류)와 확실히 구별되는 특이 케이스.
        //        스레드 안에서도 우선 적용 — 에픽은 하위작업이 될 수 없기 때문.
        if (containsEpicKeyword(cleaned)) {
            handleEpicCreate(event, cleaned);
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
            executeSubTask(event, parentIssue.getIssueKey(), content, threadTs);
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
        // STUDY: 스레드 단축형 담당자 지정 — 항상 스레드 root 이슈에 적용된다(응답에 이슈 키 명시).
        //        다른 이슈를 지정하려면 명시형 `할당 <KEY> <이름>` 사용 (routeCommand 1차에서 우선 매칭).
        if (lower.startsWith("담당자 ") || lower.startsWith("assignee ")) {
            String name = cleaned.substring(cleaned.indexOf(' ') + 1).strip();
            executeAssign(event, parentIssue.getIssueKey(), name);
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
                case "sub_task" -> executeSubTask(event, parentIssue.getIssueKey(), content, threadTs);
                case "comment" -> executeComment(event, parentIssue, content);
                case "modify" -> executeModify(event, parentIssue, content);
                case "complete" -> handleComplete(event);
                default -> replyInThread(event, threadTs,
                        ":thinking_face: 이해하지 못했어요.");
            }
        });
    }

    // STUDY: 하위작업 생성도 Jira 이슈를 만드는 행위이므로 등록 여부를 체크한다.
    //        미등록 사용자는 등록 안내 메시지를 받고, 등록된 사용자만 하위작업을 생성할 수 있다.
    //        replyTs 로 응답 위치를 통일한다 — 스레드 경로는 thread_ts, 스레드 밖 명령은 event.ts.
    private void executeSubTask(SlackEventInner event, String parentKey, String content, String replyTs) {
        slackExecutor.execute(() -> {
            try {
                // 등록 여부 확인
                var mapping = userMappingRepository.findBySlackUserId(event.user());
                if (mapping.isEmpty()) {
                    log.info("Sub-task creation blocked - unregistered user={}", event.user());
                    replyInThread(event, replyTs,
                            ":warning: Jira 계정이 연결되지 않았습니다.\n"
                            + "먼저 아래 명령으로 등록해주세요:\n"
                            + "`@지라 등록 <Jira에 표시되는 이름>`\n"
                            + "예: `@지라 등록 홍길동`\n"
                            + "등록 후 다시 시도해주세요!");
                    return;
                }

                // STUDY: Haiku 분류 → Sonnet 상세화(제목/SP) → Jira 하위작업 생성
                var intentHint = new IntentResult("register_story", 0.9, Map.of("keyword", content), content);
                var classification = issueCreateService.classifyOnly(content, intentHint);

                String jiraAccountId = mapping.get().getJiraAccountId();
                String subKey = jiraApiClient.createSubTask(
                        parentKey, classification.title(),
                        classification.storyPoint(), jiraAccountId);
                replyInThread(event, replyTs, String.format(
                        ":white_check_mark: 하위작업 생성: *%s* %s (SP %d)\n상위: %s",
                        subKey, classification.title(), classification.storyPoint(), parentKey));
            } catch (Exception e) {
                log.error("Sub-task creation failed: {}", e.toString());
                replyInThread(event, replyTs,
                        ":x: 하위작업 생성에 실패했습니다: " + e.getMessage());
            }
        });
    }

    // STUDY: 특정 부모(키/이름) 아래 하위작업 생성 — 스레드 밖 명령. 네트워크 검증(이슈 조회)이 있어
    //        slackExecutor 에서 수행한 뒤 executeSubTask 로 실제 생성을 위임한다(응답은 원 메시지 스레드).
    private void handleSubtaskCommand(SlackEventInner event, IssueCommandParser.SubtaskCommand cmd) {
        String replyTs = event.thread_ts() != null ? event.thread_ts() : event.ts();
        slackExecutor.execute(() -> {
            try {
                String parentKey = cmd.parentKey();
                if (parentKey == null) {
                    // 이름형 — 에픽 언급이면 하위작업 불가 안내(에픽 직속 하위작업 금지)
                    if (containsEpicKeyword(cmd.parentName())) {
                        replyInThread(event, replyTs,
                                ":no_entry: 에픽 아래에는 하위작업을 직접 만들 수 없어요.\n"
                                + "`<에픽명> 에픽 아래 스토리 만들어줘` 로 스토리를 연결한 뒤 그 스토리에 하위작업을 달아보세요.");
                        return;
                    }
                    var found = jiraApiClient.findIssueKeyByName(cmd.parentName());
                    if (found.isEmpty()) {
                        replyInThread(event, replyTs, String.format(
                                ":mag: '%s' 이슈를 찾을 수 없어요. 이슈 키(예: ES2-123)로 지정해보세요.", cmd.parentName()));
                        return;
                    }
                    parentKey = found.get();
                }

                if (cmd.content() == null || cmd.content().isBlank()) {
                    replyInThread(event, replyTs, String.format(
                            ":warning: 하위작업 내용을 함께 적어주세요. 예: `@지라 하위작업 %s 로그인 리팩토링`", parentKey));
                    return;
                }

                // 부모 이슈 검증 — 존재/서브태스크/에픽 여부
                var parent = jiraApiClient.getIssue(parentKey);
                if (parent.isEmpty()) {
                    replyInThread(event, replyTs, String.format(
                            ":mag: *%s* 이슈를 찾을 수 없어요. 키를 확인해주세요.", parentKey));
                    return;
                }
                var p = parent.get();
                if (p.subtask()) {
                    replyInThread(event, replyTs, String.format(
                            ":no_entry: *%s* 은(는) 하위작업이라 그 아래에 하위작업을 만들 수 없어요.", parentKey));
                    return;
                }
                if (isEpicType(p.issueType())) {
                    replyInThread(event, replyTs, String.format(
                            ":no_entry: *%s* 은(는) 에픽이라 하위작업을 직접 만들 수 없어요.\n"
                            + "`%s 에픽 아래 스토리 만들어줘` 로 스토리를 연결해보세요.", parentKey, parentKey));
                    return;
                }

                executeSubTask(event, parentKey, cmd.content(), replyTs);
            } catch (Exception e) {
                log.error("Subtask command failed: {}", e.toString());
                replyInThread(event, replyTs, ":x: 하위작업 처리 중 오류가 발생했어요: " + e.getMessage());
            }
        });
    }

    // STUDY: 이슈 링크 명령 처리 — 확신 방향은 즉시 실행, 모호하면 확인 버튼 카드.
    private void handleLinkCommand(SlackEventInner event, IssueCommandParser.LinkCommand cmd) {
        String replyTs = event.thread_ts() != null ? event.thread_ts() : event.ts();
        slackExecutor.execute(() -> {
            try {
                var type = resolveLinkType(cmd.relation());
                if (type.isEmpty()) {
                    String available = jiraApiClient.getIssueLinkTypes().stream()
                            .map(com.jirabot.slack.client.dto.IssueLinkType::name)
                            .reduce((x, y) -> x + ", " + y).orElse("(없음)");
                    replyInThread(event, replyTs, String.format(
                            ":x: '%s' 링크 타입을 찾을 수 없어요. 사용 가능: %s", cmd.relation(), available));
                    return;
                }
                var lt = type.get();

                if (cmd.ambiguous()) {
                    // 방향 모호 → 확인 버튼 (outward 설명을 동사로 표기)
                    String blocks = com.jirabot.slack.util.BlockKitBuilder.buildLinkConfirmButtons(
                            cmd.inwardKey(), cmd.outwardKey(), lt.outward(), lt.name());
                    slackNotifier.postBlockMessage(event.channel(), replyTs, "링크 방향 확인", blocks);
                    return;
                }

                boolean ok = jiraApiClient.linkIssues(cmd.inwardKey(), cmd.outwardKey(), lt.name());
                replyInThread(event, replyTs, ok
                        ? String.format(":link: *%s* %s *%s* (%s)",
                                cmd.outwardKey(), lt.outward(), cmd.inwardKey(), lt.name())
                        : String.format(":x: *%s* ↔ *%s* 링크 생성에 실패했어요.",
                                cmd.outwardKey(), cmd.inwardKey()));
            } catch (Exception e) {
                log.error("Link command failed: {}", e.toString());
                replyInThread(event, replyTs, ":x: 링크 처리 중 오류가 발생했어요: " + e.getMessage());
            }
        });
    }

    // STUDY: 관계(BLOCKS/RELATES/DUPLICATE) → 사이트에 정의된 실제 링크 타입. name/inward/outward 어디든
    //        후보 단어가 포함되면 매칭(로컬라이즈 대비, L4). POST 엔 API 가 준 정확한 name 을 쓴다.
    private Optional<com.jirabot.slack.client.dto.IssueLinkType> resolveLinkType(
            IssueCommandParser.LinkRelation rel) {
        List<String> candidates = switch (rel) {
            case BLOCKS -> List.of("blocks", "block", "차단");
            case RELATES -> List.of("relates", "relate", "관련");
            case DUPLICATE -> List.of("duplicate", "중복");
        };
        for (var t : jiraApiClient.getIssueLinkTypes()) {
            String hay = ((t.name() == null ? "" : t.name()) + " "
                    + (t.inward() == null ? "" : t.inward()) + " "
                    + (t.outward() == null ? "" : t.outward())).toLowerCase();
            for (String c : candidates) {
                if (hay.contains(c.toLowerCase())) {
                    return Optional.of(t);
                }
            }
        }
        return Optional.empty();
    }

    // STUDY: 단일 이슈 필드 수정(SP/제목/마감일/우선순위). 값이 없거나 유효하지 않으면 안내만 하고 API 호출 안 함.
    private void handleUpdateCommand(SlackEventInner event, IssueCommandParser.UpdateCommand cmd) {
        String replyTs = event.thread_ts() != null ? event.thread_ts() : event.ts();
        slackExecutor.execute(() -> {
            try {
                switch (cmd.field()) {
                    case STORY_POINT -> {
                        Integer sp = (Integer) cmd.value();
                        if (sp == null) {
                            replyInThread(event, replyTs, String.format(
                                    ":warning: 변경할 Story Point를 적어주세요. 예: `@지라 %s SP 3으로 변경`", cmd.key()));
                            return;
                        }
                        if (!IssueCommandParser.VALID_STORY_POINTS.contains(sp)) {
                            replyInThread(event, replyTs, String.format(
                                    ":warning: Story Point는 1·2·3·5·8 중 하나여야 해요 (입력: %d).\n"
                                    + "1=반나절, 2=하루, 3=1~2일, 5=2~3일, 8=3~4일(스프린트 최대)", sp));
                            return;
                        }
                        boolean ok = jiraApiClient.updateIssueFields(
                                cmd.key(), Map.of(jiraProps.storyPointField(), (double) sp));
                        if (ok) {
                            issueRepository.findByIssueKey(cmd.key()).ifPresent(i -> {
                                i.setStoryPoint((double) sp);
                                issueRepository.save(i);
                            });
                        }
                        replyInThread(event, replyTs, ok
                                ? String.format(":pencil2: *%s* Story Point를 %d로 변경했어요.", cmd.key(), sp)
                                : String.format(":x: *%s* SP 변경에 실패했어요.", cmd.key()));
                    }
                    case SUMMARY -> {
                        String title = (String) cmd.value();
                        if (title == null || title.isBlank()) {
                            replyInThread(event, replyTs, String.format(
                                    ":warning: 새 제목을 따옴표로 감싸주세요. 예: `@지라 %s 제목을 '새 제목'으로 변경`", cmd.key()));
                            return;
                        }
                        boolean ok = jiraApiClient.updateIssueFields(cmd.key(), Map.of("summary", title));
                        if (ok) {
                            issueRepository.findByIssueKey(cmd.key()).ifPresent(i -> {
                                i.setSummary(title);
                                issueRepository.save(i);
                            });
                        }
                        replyInThread(event, replyTs, ok
                                ? String.format(":pencil2: *%s* 제목을 \"%s\"(으)로 변경했어요.", cmd.key(), title)
                                : String.format(":x: *%s* 제목 변경에 실패했어요.", cmd.key()));
                    }
                    case DUE_DATE -> {
                        String token = (String) cmd.value();
                        Optional<LocalDate> date = token == null ? Optional.empty() : resolveDueDate(token);
                        if (date.isEmpty()) {
                            replyInThread(event, replyTs, String.format(
                                    ":warning: 마감일을 인식하지 못했어요. 예: `@지라 %s 마감일 2026-07-10` / `금요일` / `내일`", cmd.key()));
                            return;
                        }
                        String iso = date.get().toString();
                        boolean ok = jiraApiClient.updateIssueFields(cmd.key(), Map.of("duedate", iso));
                        replyInThread(event, replyTs, ok
                                ? String.format(":calendar: *%s* 마감일을 %s(으)로 설정했어요.", cmd.key(), iso)
                                : String.format(":x: *%s* 마감일 설정에 실패했어요.", cmd.key()));
                    }
                    case PRIORITY -> {
                        String bucket = (String) cmd.value();
                        Optional<String> name = bucket == null ? Optional.empty() : resolvePriorityName(bucket);
                        if (name.isEmpty()) {
                            String available = jiraApiClient.getPriorities().stream()
                                    .map(com.jirabot.slack.client.dto.PriorityInfo::name)
                                    .reduce((x, y) -> x + ", " + y).orElse("(없음)");
                            replyInThread(event, replyTs, String.format(
                                    ":warning: 우선순위를 인식하지 못했어요. 사용 가능: %s", available));
                            return;
                        }
                        boolean ok = jiraApiClient.updateIssueFields(
                                cmd.key(), Map.of("priority", Map.of("name", name.get())));
                        replyInThread(event, replyTs, ok
                                ? String.format(":arrow_up_small: *%s* 우선순위를 %s(으)로 변경했어요.", cmd.key(), name.get())
                                : String.format(":x: *%s* 우선순위 변경에 실패했어요.", cmd.key()));
                    }
                }
            } catch (Exception e) {
                log.error("Update command failed for {}: {}", cmd.key(), e.toString());
                replyInThread(event, replyTs, ":x: 필드 수정 중 오류가 발생했어요: " + e.getMessage());
            }
        });
    }

    // STUDY: 마감일 토큰 → KST 기준 ISO 날짜. 절대(yyyy.MM.dd / MM.dd) + 상대(오늘/내일/모레/글피/다음주) + 요일(다음 도래일).
    private Optional<LocalDate> resolveDueDate(String token) {
        String t = token.strip().toLowerCase();
        LocalDate today = LocalDate.now(KST);
        Matcher full = java.util.regex.Pattern.compile("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})").matcher(t);
        if (full.find()) {
            try {
                return Optional.of(LocalDate.of(Integer.parseInt(full.group(1)),
                        Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3))));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        switch (t) {
            case "오늘": return Optional.of(today);
            case "내일": return Optional.of(today.plusDays(1));
            case "모레": return Optional.of(today.plusDays(2));
            case "글피": return Optional.of(today.plusDays(3));
            case "다음주", "다음 주": return Optional.of(today.plusDays(7));
            default: break;
        }
        java.time.DayOfWeek dow = parseDayOfWeek(t);
        if (dow != null) {
            int diff = (dow.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
            return Optional.of(today.plusDays(diff == 0 ? 7 : diff)); // 같은 요일이면 다음 주로
        }
        Matcher md = java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2})[.\\-/](\\d{1,2})(?!\\d)").matcher(t);
        if (md.find()) {
            try {
                return Optional.of(LocalDate.of(today.getYear(),
                        Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2))));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private java.time.DayOfWeek parseDayOfWeek(String t) {
        if (t.contains("월요일") || t.contains("monday")) return java.time.DayOfWeek.MONDAY;
        if (t.contains("화요일") || t.contains("tuesday")) return java.time.DayOfWeek.TUESDAY;
        if (t.contains("수요일") || t.contains("wednesday")) return java.time.DayOfWeek.WEDNESDAY;
        if (t.contains("목요일") || t.contains("thursday")) return java.time.DayOfWeek.THURSDAY;
        if (t.contains("금요일") || t.contains("friday")) return java.time.DayOfWeek.FRIDAY;
        if (t.contains("토요일") || t.contains("saturday")) return java.time.DayOfWeek.SATURDAY;
        if (t.contains("일요일") || t.contains("sunday")) return java.time.DayOfWeek.SUNDAY;
        return null;
    }

    // STUDY: 정규 버킷(Highest/High/…) → 사이트 실제 우선순위 name. 정확 일치 우선, 없으면 부분 일치.
    private Optional<String> resolvePriorityName(String bucket) {
        var priorities = jiraApiClient.getPriorities();
        for (var p : priorities) {
            if (p.name() != null && p.name().equalsIgnoreCase(bucket)) {
                return Optional.of(p.name());
            }
        }
        for (var p : priorities) {
            if (p.name() != null && p.name().toLowerCase().contains(bucket.toLowerCase())) {
                return Optional.of(p.name());
            }
        }
        return Optional.empty();
    }

    // STUDY: 활성 스프린트로 이동. moveToActiveSprint 내부에서 활성 스프린트를 조회하므로 없으면 false.
    private void handleSprintMove(SlackEventInner event, String issueKey) {
        String replyTs = event.thread_ts() != null ? event.thread_ts() : event.ts();
        slackExecutor.execute(() -> {
            try {
                boolean ok = jiraApiClient.moveToActiveSprint(issueKey);
                replyInThread(event, replyTs, ok
                        ? String.format(":runner: *%s* 를 현재 스프린트로 옮겼어요.", issueKey)
                        : String.format(":x: *%s* 스프린트 이동에 실패했어요 (활성 스프린트가 없을 수 있어요).", issueKey));
            } catch (Exception e) {
                log.error("Sprint move failed for {}: {}", issueKey, e.toString());
                replyInThread(event, replyTs, ":x: 스프린트 이동 중 오류가 발생했어요: " + e.getMessage());
            }
        });
    }

    // STUDY: 이슈 링크 목록 조회 — 조회자 기준 관계 설명(description)으로 렌더.
    private void handleLinkList(SlackEventInner event, String issueKey) {
        String replyTs = event.thread_ts() != null ? event.thread_ts() : event.ts();
        slackExecutor.execute(() -> {
            try {
                var links = jiraApiClient.getIssueLinks(issueKey);
                if (links.isEmpty()) {
                    replyInThread(event, replyTs, String.format(":link: *%s* 에 연결된 링크가 없어요.", issueKey));
                    return;
                }
                StringBuilder sb = new StringBuilder(String.format(":link: *%s* 링크 (%d개)\n", issueKey, links.size()));
                for (var l : links) {
                    sb.append(String.format("• %s *%s*%s\n", l.description(), l.otherKey(),
                            l.otherSummary() == null ? "" : " — " + l.otherSummary()));
                }
                replyInThread(event, replyTs, sb.toString().stripTrailing());
            } catch (Exception e) {
                log.error("Link list failed for {}: {}", issueKey, e.toString());
                replyInThread(event, replyTs, ":x: 링크 조회 중 오류가 발생했어요: " + e.getMessage());
            }
        });
    }

    // STUDY: 링크 해제 — a 의 링크 중 상대가 b 인 것을 찾아 linkId 로 삭제. 재연결이 싸므로 확인 없이 즉시 실행.
    private void handleUnlink(SlackEventInner event, String a, String b) {
        String replyTs = event.thread_ts() != null ? event.thread_ts() : event.ts();
        slackExecutor.execute(() -> {
            try {
                var links = jiraApiClient.getIssueLinks(a);
                var target = links.stream()
                        .filter(l -> b.equalsIgnoreCase(l.otherKey()))
                        .findFirst();
                if (target.isEmpty() || target.get().linkId() == null) {
                    replyInThread(event, replyTs, String.format(
                            ":information_source: *%s* 와 *%s* 사이에 링크가 없어요.", a, b));
                    return;
                }
                boolean ok = jiraApiClient.deleteIssueLink(target.get().linkId());
                replyInThread(event, replyTs, ok
                        ? String.format(":broken_chain: *%s* ↔ *%s* 링크를 해제했어요.", a, b)
                        : String.format(":x: *%s* ↔ *%s* 링크 해제에 실패했어요.", a, b));
            } catch (Exception e) {
                log.error("Unlink failed for {} / {}: {}", a, b, e.toString());
                replyInThread(event, replyTs, ":x: 링크 해제 중 오류가 발생했어요: " + e.getMessage());
            }
        });
    }

    // STUDY: 이슈 타입명이 에픽인지 — 설정값(영문 정식명)과 한글 표시명 "에픽" 둘 다 비교(L4: 사이트가 한글 표시명 반환).
    private boolean isEpicType(String issueType) {
        if (issueType == null) return false;
        String t = issueType.strip();
        return t.equalsIgnoreCase(jiraProps.issueTypes().epic()) || t.equals("에픽");
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

    // STUDY: 에픽 키워드 감지. 한글 `에픽`은 앞 단어경계(앞에 한글/영문 없음)만 요구하고 뒤 조사(을/으로/...)는
    //        허용한다. 영문 `epic`은 양쪽 단어경계(\b)로 "epicenter" 같은 오탐을 막는다. 대소문자 무시.
    private static final java.util.regex.Pattern EPIC_KEYWORD_KO =
            java.util.regex.Pattern.compile("(?<![가-힣A-Za-z])에픽");
    private static final java.util.regex.Pattern EPIC_KEYWORD_EN =
            java.util.regex.Pattern.compile("(?i)\\bepic\\b");

    static boolean containsEpicKeyword(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return false;
        }
        return EPIC_KEYWORD_KO.matcher(cleaned).find() || EPIC_KEYWORD_EN.matcher(cleaned).find();
    }

    // STUDY: 에픽 생성 — 키워드 트리거 전용. register_epic 의도를 직접 만들어 서비스에 넘기면
    //        Sonnet 은 제목/요약만 추출하고 타입은 EPIC 으로 강제된다(IssueCreateServiceImpl 참고).
    private void handleEpicCreate(SlackEventInner event, String cleaned) {
        log.info("Epic creation triggered by keyword: user={} input='{}'", event.user(), cleaned);
        IntentResult intent = new IntentResult("register_epic", 1.0, Map.of("keyword", cleaned), cleaned);
        issueCreateService.createFromSlackText(IssueCreateCommand.from(event, cleaned), intent);
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
                        // STUDY: "완료 안된 task 알려줘" 처럼 미완료-한정 질의는 원문에서 결정적으로 감지해
                        //        완료 이슈를 제외한다 (의도 분류만으로는 이 조건이 소실되던 버그 수정, v0.0.59).
                        handleMyWork(event, wantsIncompleteOnly(cleaned));
                case "scrum_report" ->
                        handleScrum(event);
                case "sync_request" ->
                        handleSync(event);
                case "complete_issue" ->
                        // handleComplete 가 thread_ts 와 부모 이슈 존재 여부를 자체 가드함.
                        handleComplete(event);
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

    // STUDY: 스레드에서 호출하면 스레드에 응답, 채널에서 호출하면 채널 메시지로 응답.
    //        thread_ts가 있으면 이미 스레드 안이므로 스레드에 달고,
    //        없으면 새 메시지로 채널에 보낸다.
    private void reply(SlackEventInner event, String message) {
        if (event.channel() == null) return;
        if (event.thread_ts() != null) {
            slackNotifier.postThreadReply(event.channel(), event.thread_ts(), message);
        } else {
            slackNotifier.postMessage(event.channel(), message);
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

    // STUDY: 인사에 가볍게 응답 + 할 수 있는 일(HELP_TEXT) 안내. 안내 본문은 help 와 중복되지 않게 재사용한다.
    //        소개 문구에 연결된 Jira 프로젝트 링크를 함께 노출 — 링크는 설정(baseUrl/projectKey)에서 조립.
    private void handleGreeting(SlackEventInner event) {
        if (event.channel() != null && event.ts() != null) {
            String message = ":wave: 안녕하세요! 저는 솔루션 개발팀의 지라 봇이에요. "
                    + projectLink() + " 와 연결되어 있어요!\n"
                    + "이런 걸 도와드릴 수 있어요:\n\n" + HELP_TEXT;
            slackNotifier.postThreadReply(event.channel(), event.ts(), message);
        }
    }

    // STUDY: Jira 소프트웨어 프로젝트 보드 링크를 Slack 링크(<url|텍스트>) 형식으로 조립.
    //        설정이 비어있으면 링크 없이 일반 텍스트로 폴백.
    //        보드 ID(7)는 ES2 스크럼 보드 고정 — 멀티 프로젝트 지원은 deferred 라 현재는 ES2 전용.
    private static final String JIRA_BOARD_PATH = "/boards/7";

    private String projectLink() {
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl().replaceAll("/+$", "");
        String key = jiraProps.projectKey();
        if (base.isEmpty() || key == null || key.isBlank()) {
            return "Jira 프로젝트";
        }
        return "<" + base + "/jira/software/c/projects/" + key + JIRA_BOARD_PATH + "|" + key + " 프로젝트>";
    }

    // STUDY: 스레드에서 호출하면 스레드에 응답, 채널에서 호출하면 채널 메시지로 응답.
    //        thread_ts가 있으면 스레드 내 댓글, 없으면 일반 메시지.
    private void handleScrum(SlackEventInner event) {
        log.info("Scrum report requested by user={}", event.user());
        scrumReportService.generateReport()
                .thenAccept(report -> reply(event, report))
                .exceptionally(ex -> {
                    log.warn("Scrum report failed: {}", ex.toString());
                    replyThread(event, ":x: 스크럼 리포트 생성 중 오류가 발생했어요.");
                    return null;
                });
    }

    private void handleMyWork(SlackEventInner event) {
        handleMyWork(event, false);
    }

    // STUDY: 미완료-한정 질의("완료 안된/미완료/남은 …")를 결정적으로 감지. 부정 표현이 다양해
    //        (완료 안/완료되지 않/완료 못한/미완료/안 끝난/남은/미해결) 넓게 잡되, 긍정 질의("완료된 것")는 제외.
    private static final java.util.regex.Pattern INCOMPLETE_ONLY_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(완료\\s*(안|않|못|안된|안\\s*된|되지\\s*않)|미완료|안\\s*끝난|끝나지\\s*않|남은|남아\\s*있|미해결"
                    + "|not\\s+done|incomplete|unfinished|remaining|open\\s+tasks?)");

    static boolean wantsIncompleteOnly(String text) {
        return text != null && INCOMPLETE_ONLY_PATTERN.matcher(text).find();
    }

    private void handleMyWork(SlackEventInner event, boolean excludeDone) {
        log.info("My work requested by user={} excludeDone={}", event.user(), excludeDone);
        scrumReportService.generateMyReport(event.user(), excludeDone)
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
            // STUDY: 수동 동기화도 전체 동기화(활성+백로그+삭제 정리)로 처리.
            String result = jiraSyncService.fullSync();
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

    // STUDY: 리마인더 명령 — 호출자의 opt-in 상태를 토글한다.
    //        on / off / 상태(status) 세 가지 인자를 받는다. 그 외는 안내 메시지.
    private void handleReminder(SlackEventInner event, String arg) {
        log.info("Reminder command requested arg='{}' user={}", arg, event.user());
        String userId = event.user();
        if (userId == null || userId.isBlank()) {
            replyThread(event, ":warning: 호출자 정보를 식별할 수 없습니다.");
            return;
        }
        String result = switch (arg) {
            case "on" -> reminderSubscriptionService.enable(userId);
            case "off" -> reminderSubscriptionService.disable(userId);
            case "상태", "status" -> reminderSubscriptionService.status(userId);
            default -> ":warning: 사용법: `@지라 리마인더 on` / `off` / `상태`";
        };
        replyThread(event, result);
    }

    // STUDY: 할당알림 명령 — handleReminder 와 동일 패턴. Jira 할당 DM 수신(assignDmEnabled) 토글.
    private void handleAssignDm(SlackEventInner event, String arg) {
        log.info("Assign-DM command requested arg='{}' user={}", arg, event.user());
        String userId = event.user();
        if (userId == null || userId.isBlank()) {
            replyThread(event, ":warning: 호출자 정보를 식별할 수 없습니다.");
            return;
        }
        String result = switch (arg) {
            case "on" -> reminderSubscriptionService.enableAssignDm(userId);
            case "off" -> reminderSubscriptionService.disableAssignDm(userId);
            case "상태", "status" -> reminderSubscriptionService.assignDmStatus(userId);
            default -> ":warning: 사용법: `@지라 할당알림 on` / `off` / `상태`";
        };
        replyThread(event, result);
    }

    // STUDY: 이슈 키 카드 트리거 판정. 메시지에서 이슈 키 1개를 찾고, 키를 뺀 나머지가
    //        비어있거나 조회성 단어(보여줘/상세/조회/show...)뿐이면 그 키를 반환한다.
    //        나머지에 다른 내용이 있으면(서술문) null — 이슈 생성/검색 흐름을 가로채지 않는다.
    private static final java.util.regex.Pattern ISSUE_KEY_PATTERN =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9]*-\\d+");
    private static final java.util.regex.Pattern CARD_FILLER_PATTERN = java.util.regex.Pattern.compile(
            "(?i)^(이슈|조회|카드|보여줘|알려줘|상세|정보|상태|확인|show|info|detail|status|[\\s!?.~,]+)*$");

    static String extractCardIssueKey(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return null;
        }
        Matcher m = ISSUE_KEY_PATTERN.matcher(cleaned);
        if (!m.find()) {
            return null;
        }
        String key = m.group();
        if (m.find()) {
            return null; // 키가 2개 이상이면 의도가 불분명 — 가로채지 않는다.
        }
        String remainder = cleaned.replace(key, " ");
        return CARD_FILLER_PATTERN.matcher(remainder).matches() ? key : null;
    }

    private void handleIssueCard(SlackEventInner event, String issueKey) {
        log.info("Issue card requested key={} by user={}", issueKey, event.user());
        slackExecutor.execute(() -> {
            try {
                String url = issueLink(issueKey);
                Optional<IssueEntity> local = issueRepository.findByIssueKey(issueKey);
                String blocksJson;
                if (local.isPresent()) {
                    IssueEntity i = local.get();
                    blocksJson = com.jirabot.slack.util.BlockKitBuilder.buildIssueCardBlocks(
                            i.getIssueKey(), url, i.getSummary(), i.getIssueType(),
                            i.getStatus(), i.getStatusCategory(), i.getAssignee(), i.getReporter(),
                            i.getStoryPoint(), i.getSprintName(), i.getDescription());
                } else {
                    // STUDY: 로컬 미보유(다른 보드/이미 prune 된 완료 이슈 등) → Jira 라이브 단건 조회 폴백.
                    var live = jiraApiClient.getIssue(issueKey);
                    if (live.isEmpty()) {
                        replyThread(event, String.format(":mag: *%s* 이슈를 찾을 수 없어요. 키를 확인해주세요.", issueKey));
                        return;
                    }
                    var s = live.get();
                    blocksJson = com.jirabot.slack.util.BlockKitBuilder.buildIssueCardBlocks(
                            s.key(), url, s.summary(), s.issueType(), s.status(), s.statusCategory(),
                            s.assignee(), s.reporter(), s.storyPoint(), null, null);
                }
                slackNotifier.postBlockMessage(event.channel(), event.ts(),
                        String.format("[%s] 이슈 카드", issueKey), blocksJson);
            } catch (Exception e) {
                log.warn("Issue card failed key={}: {}", issueKey, e.toString());
                replyThread(event, ":x: 이슈 조회 중 오류가 발생했어요.");
            }
        });
    }

    // STUDY: 명시형 담당자 지정 파싱 — "<KEY> <이름|@멘션>". 형식이 어긋나면 사용법 안내.
    private static final java.util.regex.Pattern ASSIGN_ARGS_PATTERN =
            java.util.regex.Pattern.compile("^([A-Z][A-Z0-9]*-\\d+)\\s+(.+)$");
    private static final java.util.regex.Pattern SLACK_MENTION_PATTERN =
            java.util.regex.Pattern.compile("^<@([A-Z0-9]+)>$");

    private void handleAssign(SlackEventInner event, String args) {
        Matcher m = ASSIGN_ARGS_PATTERN.matcher(args);
        if (!m.matches()) {
            replyThread(event, ":warning: 사용법: `@지라 할당 <이슈키> <이름 또는 @멘션>`\n예: `@지라 할당 ES2-123 홍길동`");
            return;
        }
        executeAssign(event, m.group(1), m.group(2).strip());
    }

    private void executeAssign(SlackEventInner event, String issueKey, String assigneeText) {
        log.info("Assign requested key={} assignee='{}' by user={}", issueKey, assigneeText, event.user());
        slackExecutor.execute(() -> {
            try {
                String accountId;
                String displayName;
                Matcher mention = SLACK_MENTION_PATTERN.matcher(assigneeText);
                if (mention.matches()) {
                    var mapping = userMappingRepository.findBySlackUserId(mention.group(1));
                    if (mapping.isEmpty() || mapping.get().getJiraAccountId() == null) {
                        reply(event, String.format(
                                ":warning: <@%s> 님의 Jira 매핑이 없습니다. 본인이 `@지라 등록 <Jira 사용자명>` 으로 먼저 등록해야 해요.",
                                mention.group(1)));
                        return;
                    }
                    accountId = mapping.get().getJiraAccountId();
                    displayName = mapping.get().getJiraDisplayName();
                } else {
                    // STUDY: 이름 해석 — 등록된 매핑(정확 일치) 우선, 없으면 Jira user search 폴백.
                    var mapping = userMappingRepository.findByJiraDisplayName(assigneeText);
                    if (mapping.isPresent() && mapping.get().getJiraAccountId() != null) {
                        accountId = mapping.get().getJiraAccountId();
                        displayName = mapping.get().getJiraDisplayName();
                    } else {
                        accountId = jiraApiClient.findAccountId(assigneeText);
                        displayName = assigneeText;
                        if (accountId == null) {
                            reply(event, String.format(
                                    ":x: Jira에서 *%s* 사용자를 찾을 수 없습니다. Jira에 표시되는 이름으로 다시 시도해주세요.",
                                    assigneeText));
                            return;
                        }
                    }
                }

                boolean ok = jiraApiClient.assignIssue(issueKey, accountId);
                if (!ok) {
                    reply(event, String.format(":x: *%s* 담당자 변경에 실패했습니다. 이슈 키를 확인해주세요.", issueKey));
                    return;
                }
                // 로컬 DB 즉시 반영 (추적 중인 이슈만 — 다음 sync 전까지의 gap 메움)
                String finalName = displayName;
                issueRepository.findByIssueKey(issueKey).ifPresent(i -> {
                    i.setAssignee(finalName);
                    issueRepository.save(i);
                });
                // STUDY: 응답에 항상 대상 이슈 키를 명시 — 스레드 단축형이 의도와 다른 이슈를 바꿨다면 즉시 보이게.
                reply(event, String.format(":bust_in_silhouette: *%s* 담당자를 *%s* 님으로 지정했어요.",
                        issueKey, displayName));
            } catch (Exception e) {
                log.error("Assign failed key={}: {}", issueKey, e.toString());
                reply(event, ":x: 담당자 지정 중 오류가 발생했어요.");
            }
        });
    }

    private String issueLink(String key) {
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl().replaceAll("/+$", "");
        return base.isEmpty() ? key : base + "/browse/" + key;
    }

    // STUDY: Jira 전체 버그를 Notion '버그 현황' DB 로 백필. 건수가 많아 비동기로 실행하고 결과만 회신.
    private void handleNotionBackfill(SlackEventInner event) {
        if (!bugNotionService.enabled()) {
            replyThread(event, ":warning: Notion 연동이 비활성 상태입니다. (NOTION_TOKEN 확인)");
            return;
        }
        replyThread(event, ":hourglass_flowing_sand: 버그 현황을 Notion에 백필 중입니다…");
        slackExecutor.execute(() -> {
            try {
                int count = bugNotionService.backfillStatusDb();
                replyThread(event, String.format(":notebook: 버그 현황 백필 완료 — %d건 정리했습니다.", count));
            } catch (Exception e) {
                log.error("Notion backfill failed: {}", e.toString());
                replyThread(event, ":x: 백필 중 오류가 발생했어요.");
            }
        });
    }

    // STUDY: 완료된 PR → Jira 티켓. GitHub/Claude/Jira 다단계라 느려서 async. Slack unfurl 로 들어오는
    //        `<https://...|text>` 형태에서 URL 만 추출한다.
    private void handlePrImport(SlackEventInner event, String arg) {
        // 자연어/unfurl(<url|label>) 어디에 있든 첫 GitHub PR URL 을 정규식으로 추출.
        java.util.regex.Matcher m = arg == null ? null : GITHUB_PR_URL.matcher(arg);
        if (m == null || !m.find()) {
            replyThread(event, ":mag: GitHub PR URL 을 찾지 못했어요. 예: "
                    + "`@지라 https://github.com/조직/repo/pull/123 관련 티켓 만들어줘`");
            return;
        }
        final String prUrl = m.group();
        replyThread(event, ":hourglass_flowing_sand: PR 내용을 읽고 티켓을 만드는 중입니다… (PR 상태에 맞춰 진행 중/검토 중/완료까지 전환)");
        final String slackUserId = event.user();
        slackExecutor.execute(() -> {
            try {
                PrImportService.Result r = prImportService.importPr(prUrl, slackUserId);
                if (!r.success()) {
                    replyThread(event, ":x: " + r.message());
                    return;
                }
                replyThread(event, String.format(
                        ":white_check_mark: <%s|%s> 등록 완료 — 영업일 %.1f일 → SP %d, 상태 *%s*, 보고자/담당자 *%s* (현재 스프린트)",
                        r.issueUrl(), r.issueKey(), r.businessDays(), r.storyPoint(), r.finalStatus(),
                        r.assignee() == null ? "미지정" : r.assignee()));
            } catch (Exception e) {
                log.error("PR import failed for {}: {}", prUrl, e.toString(), e);
                replyThread(event, ":x: PR 등록 중 오류가 발생했어요.");
            }
        });
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
            if (isStaleEvent(event.ts())) {
                log.info("Ignoring stale event ts={} (older than {}s)", event.ts(), STALE_EVENT_SECONDS);
                replyThread(event, ":hourglass: 일정 시간이 지난 요청이라 처리하지 않았습니다. 다시 보내주세요.");
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
