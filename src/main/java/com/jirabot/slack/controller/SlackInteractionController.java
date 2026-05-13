package com.jirabot.slack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.AsyncConfig;
import com.jirabot.slack.dto.SlackInteractionPayload;
import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.util.BlockKitBuilder;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    private final Executor slackExecutor;

    public SlackInteractionController(ObjectMapper objectMapper,
                                      JiraApiClient jiraApiClient,
                                      SlackNotifier slackNotifier,
                                      IssueRepository issueRepository,
                                      @Qualifier(AsyncConfig.SLACK_EXECUTOR) Executor slackExecutor) {
        this.objectMapper = objectMapper;
        this.jiraApiClient = jiraApiClient;
        this.slackNotifier = slackNotifier;
        this.issueRepository = issueRepository;
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
    //   진행 중: 해야 할 일 → 진행 중 + 활성 스프린트 이동. 다음 버튼: 검토 중
    //   검토 중: 진행 중 → 검토 중. 다음 버튼: 완료
    //   완료: → 완료 상태. 버튼 없음.
    //   바로 완료: 해야 할 일 → 진행 중 → 완료 + 스프린트 이동 한번에.
    private void handleTransition(String actionId, String issueKey, String userName,
                                  SlackInteractionPayload payload) {
        String channelId = payload.channel() != null ? payload.channel().id() : null;
        String messageTs = payload.message() != null ? payload.message().ts() : null;
        JsonNode originalBlocks =
                payload.message() != null ? payload.message().blocks() : null;

        switch (actionId) {
            case BlockKitBuilder.ACTION_TODO ->
                    doTransition(issueKey, "해야 할 일", ":clipboard:", "해야 할 일",
                            BlockKitBuilder.ACTION_IN_PROGRESS, "\ud83d\udd28 진행 중",
                            userName, channelId, messageTs, originalBlocks, false);
            case BlockKitBuilder.ACTION_IN_PROGRESS ->
                    doTransition(issueKey, "진행 중", ":hammer_and_wrench:", "진행 중",
                            BlockKitBuilder.ACTION_IN_REVIEW, "\ud83d\udd0d 검토 중",
                            userName, channelId, messageTs, originalBlocks, true);
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
