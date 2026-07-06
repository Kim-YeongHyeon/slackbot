package com.jirabot.slack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.GitHubApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.BranchResult;
import com.jirabot.slack.config.AsyncConfig;
import com.jirabot.slack.config.GitHubProperties;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.dto.SlackInteractionPayload;
import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.util.BlockKitBuilder;
import com.jirabot.slack.util.BranchNameBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: Slack interactive payloads arrive as application/x-www-form-urlencoded with a single
//        "payload" parameter containing the JSON body. The controller must parse this form param
//        and respond within 3 seconds — heavy work (Jira transition) runs async.
@RestController
@RequestMapping("/api/slack")
public class SlackInteractionController {

    private static final Logger log = LoggerFactory.getLogger(SlackInteractionController.class);

    private final ObjectMapper objectMapper;
    private final JiraApiClient jiraApiClient;
    private final SlackNotifier slackNotifier;
    private final IssueRepository issueRepository;
    private final ClaudeApiClient claudeApiClient;
    private final GitHubApiClient gitHubApiClient;
    private final GitHubProperties gitHubProps;
    private final JiraProperties jiraProps;
    private final Executor slackExecutor;

    public SlackInteractionController(ObjectMapper objectMapper,
                                      JiraApiClient jiraApiClient,
                                      SlackNotifier slackNotifier,
                                      IssueRepository issueRepository,
                                      ClaudeApiClient claudeApiClient,
                                      GitHubApiClient gitHubApiClient,
                                      GitHubProperties gitHubProps,
                                      JiraProperties jiraProps,
                                      @Qualifier(AsyncConfig.SLACK_EXECUTOR) Executor slackExecutor) {
        this.objectMapper = objectMapper;
        this.jiraApiClient = jiraApiClient;
        this.slackNotifier = slackNotifier;
        this.issueRepository = issueRepository;
        this.claudeApiClient = claudeApiClient;
        this.gitHubApiClient = gitHubApiClient;
        this.gitHubProps = gitHubProps;
        this.jiraProps = jiraProps;
        this.slackExecutor = slackExecutor;
    }

    @PostMapping(path = "/interaction", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> onInteraction(HttpServletRequest request) {
        try {
            String payloadJson = extractPayloadFromBody(request);
            SlackInteractionPayload payload = objectMapper.readValue(payloadJson,
                    SlackInteractionPayload.class);

            if (!"block_actions".equals(payload.type())) {
                log.debug("Ignoring interaction type={}", payload.type());
                return ResponseEntity.ok("");
            }

            if (payload.actions() == null || payload.actions().isEmpty()) {
                log.warn("block_actions with no actions");
                return ResponseEntity.ok("");
            }

            SlackInteractionPayload.SlackAction action = payload.actions().get(0);
            String actionId = action.actionId();
            String issueKey = action.value();
            String userName = payload.user() != null ? payload.user().name() : "unknown";

            log.info("Interaction received: action={} issueKey={} user={}", actionId, issueKey, userName);

            slackExecutor.execute(() -> handleTransition(actionId, issueKey, userName, payload));

            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("Failed to parse interaction payload: {}", e.toString(), e);
            return ResponseEntity.ok("");
        }
    }

    // STUDY: 각 버튼의 워크플로:
    //   해야 할 일: Backlog → 해야 할 일 (Kanban→Scrum backlog). 다음 버튼: 진행 중
    //   진행 중: (Backlog →) 해야 할 일 → 진행 중 + 활성 스프린트 이동. 다음 버튼: 검토 중
    //            Jira 워크플로상 Backlog에서 진행 중으로 직접 전환 불가 — 해야 할 일을 먼저 거친다.
    //   검토 중: 진행 중 → 검토 중. 다음 버튼: 완료
    //   완료: → 완료 상태. 버튼 없음.
    //   바로 완료: 해야 할 일 → 진행 중 → 완료 + 스프린트 이동 한번에.
    private void handleTransition(String actionId, String issueKey, String userName,
                                  SlackInteractionPayload payload) {
        String channelId = payload.channel() != null ? payload.channel().id() : null;
        String messageTs = payload.message() != null ? payload.message().ts() : null;
        JsonNode originalBlocks =
                payload.message() != null ? payload.message().blocks() : null;

        // STUDY: 브랜치 버튼은 repo 마다 action_id 가 고유(jira_create_branch_N)하므로 prefix 로 판별한다.
        //        value = "issueKey|repo|branch" 에 모든 정보가 있어 action_id 의 인덱스는 라우팅에만 쓴다.
        if (actionId != null && actionId.startsWith(BlockKitBuilder.ACTION_CREATE_BRANCH)) {
            handleCreateBranch(issueKey, channelId, messageTs);
            return;
        }

        // STUDY: 링크 방향 확인 버튼 — action_id 는 ACTION_LINK_CONFIRM prefix(_1/_2/_cancel), value 에 정보가 있다.
        if (actionId != null && actionId.startsWith(BlockKitBuilder.ACTION_LINK_CONFIRM)) {
            handleLinkConfirm(issueKey, userName, channelId, messageTs);
            return;
        }

        switch (actionId) {
            case BlockKitBuilder.ACTION_TODO ->
                    doTransition(issueKey, "해야 할 일", ":clipboard:", "해야 할 일",
                            BlockKitBuilder.ACTION_IN_PROGRESS, "\ud83d\udd28 진행 중",
                            userName, channelId, messageTs, originalBlocks, false);
            case BlockKitBuilder.ACTION_IN_PROGRESS ->
                    handleInProgress(issueKey, userName, channelId, messageTs, originalBlocks);
            case BlockKitBuilder.ACTION_IN_REVIEW ->
                    doTransition(issueKey, "검토 중", ":mag:", "검토 중",
                            BlockKitBuilder.ACTION_DONE, "\u2705 완료",
                            userName, channelId, messageTs, originalBlocks, false);
            case BlockKitBuilder.ACTION_DONE ->
                    doTransition(issueKey, "완료", ":white_check_mark:", "완료",
                            null, null,
                            userName, channelId, messageTs, originalBlocks, false);
            case BlockKitBuilder.ACTION_QUICK_DONE ->
                    handleQuickDone(issueKey, userName, channelId, messageTs, originalBlocks);
            default -> log.warn("Unknown action_id: {}", actionId);
        }
    }

    // STUDY: 진행 중 버튼 — Jira 워크플로상 Backlog에서 진행 중으로 직접 전환이 불가하므로
    //        먼저 "해야 할 일"로 전환을 시도한다. 이미 해야 할 일 상태라면 실패해도 무시하고 계속 진행.
    //        최종적으로 "진행 중" 전환 + 스프린트 이동을 수행한다.
    private void handleInProgress(String issueKey, String userName, String channelId,
                                  String messageTs, JsonNode originalBlocks) {
        try {
            // Step 1: Backlog → 해야 할 일 (이미 해야 할 일이면 실패해도 무시)
            if (jiraApiClient.transitionIssue(issueKey, "해야 할 일")) {
                updateDbStatus(issueKey, "해야 할 일");
            }

            // Step 2: 해야 할 일 → 진행 중
            boolean success = jiraApiClient.transitionIssue(issueKey, "진행 중");
            if (success) {
                jiraApiClient.moveToActiveSprint(issueKey);
                updateDbStatus(issueKey, "진행 중");
                if (channelId != null && messageTs != null) {
                    String resultText = String.format(
                            ":hammer_and_wrench: *%s* → 진행 중 (by %s)", issueKey, userName);
                    String updatedBlocks = BlockKitBuilder.buildTransitionedBlocks(
                            issueKey, ":hammer_and_wrench:", "진행 중", userName,
                            BlockKitBuilder.ACTION_IN_REVIEW, "\ud83d\udd0d 검토 중", originalBlocks);
                    slackNotifier.updateMessage(channelId, messageTs, resultText, updatedBlocks);
                }
                // STUDY: 진행 중 전환 후 브랜치 만들기 안내. github 토큰이 설정돼 있으면 repo 선택 버튼을,
                //        아니면 기존 Jira 개발 패널 힌트를 게시한다.
                postBranchOptions(issueKey, channelId, messageTs);
            } else {
                notifyFailure(channelId, messageTs, issueKey, "진행 중");
            }
        } catch (Exception e) {
            log.error("InProgress transition error for {}: {}", issueKey, e.toString(), e);
            if (channelId != null && messageTs != null) {
                slackNotifier.postThreadReply(channelId, messageTs,
                        String.format(":x: *%s* 진행 중 전환 중 오류: %s", issueKey, e.getMessage()));
            }
        }
    }

    // STUDY: "Jira 를 통한 브랜치 생성" — 네이티브 GitHub for Jira 앱은 create-branch 딥링크를 제공하지 않으므로
    //        (atlassian/github-for-jira#402), 봇은 이슈 개발 패널 링크 + 규칙 기반 권장 브랜치명을 안내하고
    //        실제 생성은 사용자가 Jira 개발 패널의 "브랜치 만들기"에서 대상 레포·base 를 선택해 수행한다.
    //        브랜치명은 영어로 통일: 한글 요약은 Claude(englishBranchSlug)로 영어 슬러그를 만들어 사용하고,
    //        변환 실패 시 issueKey 만으로 브랜치명을 만든다.
    private void postBranchOptions(String issueKey, String channelId, String messageTs) {
        if (channelId == null || messageTs == null) {
            return;
        }
        try {
            Optional<IssueEntity> found = issueRepository.findByIssueKey(issueKey);
            String issueType = found.map(IssueEntity::getIssueType).orElse(null);
            String summary = found.map(IssueEntity::getSummary).orElse(null);
            // STUDY: 영어 브랜치명은 여기서 1회 계산해 버튼 value 에 실어 보낸다 → 클릭 시 Claude 재호출 없음.
            String branch = BranchNameBuilder.build(issueType, issueKey, claudeApiClient.englishBranchSlug(summary));

            if (gitHubProps.enabled()) {
                String blocks = BlockKitBuilder.buildBranchRepoButtons(
                        issueKey, branch, gitHubProps.branchRepos());
                slackNotifier.postBlockMessage(channelId, messageTs,
                        ":herb: 브랜치를 만들 repo를 선택하세요", blocks);
            } else {
                // 폴백: GitHub 토큰 미설정 → 기존 Jira 개발 패널 힌트.
                String message = String.format(
                        ":herb: 브랜치 만들기: <%s|%s> 우측 *개발(Development)* 패널 → \"브랜치 만들기\"\n"
                                + "권장 브랜치명: `%s`",
                        issueLink(issueKey), issueKey, branch);
                slackNotifier.postThreadReply(channelId, messageTs, message);
            }
        } catch (Exception e) {
            log.warn("Branch options failed for {}: {}", issueKey, e.toString());
        }
    }

    // STUDY: repo 버튼 클릭 → GitHub API 로 브랜치 생성. value = "issueKey|repo|branch".
    private void handleCreateBranch(String value, String channelId, String messageTs) {
        if (channelId == null || messageTs == null) {
            return;
        }
        String[] parts = value == null ? new String[0] : value.split("\\|", 3);
        if (parts.length < 3) {
            log.warn("Bad create-branch value: {}", value);
            return;
        }
        String repo = parts[1];
        String branch = parts[2];
        try {
            BranchResult result = gitHubApiClient.createBranch(repo, branch);
            String msg = switch (result.status()) {
                case CREATED -> String.format(":herb: 브랜치 생성 완료: <%s|%s/%s>", result.htmlUrl(), repo, branch);
                case ALREADY_EXISTS -> String.format(":information_source: 이미 있는 브랜치예요: <%s|%s/%s>",
                        result.htmlUrl(), repo, branch);
                case FAILED -> String.format(":x: 브랜치 생성 실패 (%s): %s", repo, result.message());
            };
            slackNotifier.postThreadReply(channelId, messageTs, msg);
        } catch (Exception e) {
            log.error("Create-branch error value={}: {}", value, e.toString(), e);
            slackNotifier.postThreadReply(channelId, messageTs, ":x: 브랜치 생성 중 오류가 발생했어요.");
        }
    }

    // STUDY: 링크 방향 확인 버튼 클릭 처리. value = "inwardKey|outwardKey|typeName" 또는 "cancel".
    //        확정 시 linkIssues 호출 후 원 메시지의 버튼을 제거(updateMessage)한다.
    private void handleLinkConfirm(String value, String userName, String channelId, String messageTs) {
        if ("cancel".equals(value)) {
            if (channelId != null && messageTs != null) {
                slackNotifier.updateMessage(channelId, messageTs, ":x: 링크를 취소했어요.", "[]");
            }
            return;
        }
        String[] parts = value == null ? new String[0] : value.split("\\|", 3);
        if (parts.length < 3) {
            log.warn("Bad link-confirm value: {}", value);
            return;
        }
        String inward = parts[0], outward = parts[1], typeName = parts[2];
        try {
            boolean ok = jiraApiClient.linkIssues(inward, outward, typeName);
            if (channelId != null && messageTs != null) {
                String msg = ok
                        ? String.format(":link: *%s* ↔ *%s* 링크를 만들었어요 (%s, by %s).",
                                outward, inward, typeName, userName)
                        : String.format(":x: *%s* ↔ *%s* 링크 생성에 실패했어요.", outward, inward);
                slackNotifier.updateMessage(channelId, messageTs, msg, "[]");
            }
        } catch (Exception e) {
            log.error("Link confirm error value={}: {}", value, e.toString(), e);
            if (channelId != null && messageTs != null) {
                slackNotifier.postThreadReply(channelId, messageTs, ":x: 링크 생성 중 오류가 발생했어요.");
            }
        }
    }

    private String issueLink(String issueKey) {
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        if (base.isBlank()) {
            return issueKey;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + issueKey;
    }

    private void doTransition(String issueKey, String targetStatus, String statusEmoji,
                              String statusLabel, String nextActionId, String nextLabel,
                              String userName, String channelId, String messageTs,
                              JsonNode originalBlocks,
                              boolean moveToSprint) {
        try {
            boolean success = jiraApiClient.transitionIssue(issueKey, targetStatus);
            if (success) {
                if (moveToSprint) {
                    jiraApiClient.moveToActiveSprint(issueKey);
                }
                updateDbStatus(issueKey, targetStatus);
                if (channelId != null && messageTs != null) {
                    String resultText = String.format(
                            "%s *%s* \u2192 %s (by %s)", statusEmoji, issueKey, statusLabel, userName);
                    String updatedBlocks = BlockKitBuilder.buildTransitionedBlocks(
                            issueKey, statusEmoji, statusLabel, userName,
                            nextActionId, nextLabel, originalBlocks);
                    slackNotifier.updateMessage(channelId, messageTs, resultText, updatedBlocks);
                }
            } else {
                notifyFailure(channelId, messageTs, issueKey, statusLabel);
            }
        } catch (Exception e) {
            log.error("Transition error for {} \u2192 {}: {}", issueKey, targetStatus, e.toString(), e);
            if (channelId != null && messageTs != null) {
                slackNotifier.postThreadReply(channelId, messageTs,
                        String.format(":x: *%s* 상태 변경 중 오류: %s", issueKey, e.getMessage()));
            }
        }
    }

    // STUDY: 바로 완료는 해야 할 일 → 진행 중 → 완료를 순차 실행.
    //        각 단계 성공 시 DB를 업데이트하여 중간 실패 시에도 일관된 상태를 유지.
    //        이미 해당 상태인 경우(transition 실패) 건너뛰고 계속 진행.
    private void handleQuickDone(String issueKey, String userName, String channelId,
                                 String messageTs,
                                 JsonNode originalBlocks) {
        try {
            // Step 1: Backlog → 해야 할 일
            if (jiraApiClient.transitionIssue(issueKey, "해야 할 일")) {
                updateDbStatus(issueKey, "해야 할 일");
            }

            // Step 2: 해야 할 일 → 진행 중
            if (jiraApiClient.transitionIssue(issueKey, "진행 중")) {
                updateDbStatus(issueKey, "진행 중");
            }

            // 스프린트 이동은 전환 성공 여부와 무관하게 시도 (이미 진행 중일 수 있음)
            jiraApiClient.moveToActiveSprint(issueKey);

            // Step 3: 진행 중 → 완료
            boolean done = jiraApiClient.transitionIssue(issueKey, "완료");
            if (done) {
                updateDbStatus(issueKey, "완료");
                if (channelId != null && messageTs != null) {
                    String resultText = String.format(
                            ":zap: *%s* \u2192 바로 완료 (by %s)", issueKey, userName);
                    String updatedBlocks = BlockKitBuilder.buildCompletedBlocks(
                            issueKey, ":zap:", "바로 완료", userName, originalBlocks);
                    slackNotifier.updateMessage(channelId, messageTs, resultText, updatedBlocks);
                }
            } else {
                notifyFailure(channelId, messageTs, issueKey, "바로 완료");
            }
        } catch (Exception e) {
            log.error("Quick done error for {}: {}", issueKey, e.toString(), e);
            if (channelId != null && messageTs != null) {
                slackNotifier.postThreadReply(channelId, messageTs,
                        String.format(":x: *%s* 바로 완료 중 오류: %s", issueKey, e.getMessage()));
            }
        }
    }

    private void updateDbStatus(String issueKey, String targetStatus) {
        // STUDY: status와 statusCategory는 다르다.
        //        status="검토 중" → statusCategory="진행 중", status="완료" → statusCategory="완료" 등.
        String category = resolveCategory(targetStatus);
        issueRepository.findByIssueKey(issueKey).ifPresent(issue -> {
            issue.updateStatus(targetStatus, category);
            issueRepository.save(issue);
            log.info("DB updated: {} \u2192 {} (category={})", issueKey, targetStatus, category);
        });
    }

    // STUDY: Jira 상태명 → statusCategory 매핑. Jira API에서는 3가지 카테고리만 존재.
    private String resolveCategory(String statusName) {
        return switch (statusName) {
            case "완료" -> StatusCategory.DONE;
            case "진행 중", "검토 중" -> StatusCategory.IN_PROGRESS;
            default -> StatusCategory.TODO;
        };
    }

    private void notifyFailure(String channelId, String messageTs, String issueKey, String statusLabel) {
        log.warn("Jira transition failed for {} \u2192 {}", issueKey, statusLabel);
        if (channelId != null && messageTs != null) {
            slackNotifier.postThreadReply(channelId, messageTs,
                    String.format(":x: *%s* \u2192 %s 전환에 실패했습니다. Jira에서 직접 확인해주세요.",
                            issueKey, statusLabel));
        }
    }

    // STUDY: CachedBodyFilter가 body를 먼저 읽으므로 Tomcat의 form 파라미터 파싱이 동작하지 않는다.
    //        cached raw body에서 "payload=" 접두사를 제거하고 URL 디코딩하여 JSON을 추출.
    private String extractPayloadFromBody(HttpServletRequest request) {
        Object cached = request.getAttribute(CachedBodyFilter.RAW_BODY_ATTRIBUTE);
        String body;
        if (cached instanceof byte[] bytes) {
            body = new String(bytes, StandardCharsets.UTF_8);
        } else {
            try {
                body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read request body", e);
            }
        }
        String prefix = "payload=";
        if (body.startsWith(prefix)) {
            return URLDecoder.decode(body.substring(prefix.length()), StandardCharsets.UTF_8);
        }
        return body;
    }
}
