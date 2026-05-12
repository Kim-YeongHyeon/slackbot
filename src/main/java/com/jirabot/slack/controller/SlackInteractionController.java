package com.jirabot.slack.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.AsyncConfig;
import com.jirabot.slack.dto.SlackInteractionPayload;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    // STUDY: consumes APPLICATION_FORM_URLENCODED_VALUE — Slack은 interaction payload를
    //        form-encoded body의 "payload" 파라미터로 전송한다. Spring이 자동으로 form 파싱.
    @PostMapping(path = "/interaction", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> onInteraction(@RequestParam("payload") String payloadJson) {
        try {
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
            String channelId = payload.channel() != null ? payload.channel().id() : null;
            String messageTs = payload.message() != null ? payload.message().ts() : null;
            String userName = payload.user() != null ? payload.user().name() : "unknown";

            log.info("Interaction received: action={} issueKey={} user={}", actionId, issueKey, userName);

            // STUDY: Slack requires a response within 3 seconds. Immediately acknowledge,
            //        then perform Jira transition + message update asynchronously.
            slackExecutor.execute(() ->
                    handleTransition(actionId, issueKey, channelId, messageTs, userName));

            return ResponseEntity.ok("");
        } catch (Exception e) {
            log.error("Failed to parse interaction payload: {}", e.toString(), e);
            return ResponseEntity.ok("");
        }
    }

    private void handleTransition(String actionId, String issueKey, String channelId,
                                  String messageTs, String userName) {
        String targetStatus;
        String statusEmoji;
        String statusLabel;

        switch (actionId) {
            case "jira_transition_in_progress" -> {
                targetStatus = "진행 중";
                statusEmoji = ":hammer_and_wrench:";
                statusLabel = "진행 중";
            }
            case "jira_transition_done" -> {
                targetStatus = "완료";
                statusEmoji = ":white_check_mark:";
                statusLabel = "완료";
            }
            default -> {
                log.warn("Unknown action_id: {}", actionId);
                return;
            }
        }

        try {
            boolean success = jiraApiClient.transitionIssue(issueKey, targetStatus);
            if (success) {
                // DB 업데이트
                issueRepository.findByIssueKey(issueKey).ifPresent(issue -> {
                    issue.updateFrom(issue.getSummary(), issue.getIssueType(),
                            targetStatus, targetStatus,
                            issue.getAssignee(), issue.getStoryPoint(), Instant.now());
                    issueRepository.save(issue);
                    log.info("DB updated: {} → {}", issueKey, targetStatus);
                });

                // 원본 메시지 업데이트: 버튼 제거 + 결과 표시
                if (channelId != null && messageTs != null) {
                    String resultText = String.format(
                            "%s *%s* → %s (by %s)", statusEmoji, issueKey, statusLabel, userName);
                    String updatedBlocks = buildCompletedBlocks(issueKey, statusEmoji, statusLabel, userName);
                    slackNotifier.updateMessage(channelId, messageTs, resultText, updatedBlocks);
                }
            } else {
                log.warn("Jira transition failed for {} → {}", issueKey, targetStatus);
                if (channelId != null && messageTs != null) {
                    // STUDY: 전환 실패 시 스레드 댓글로 에러 알림. 원본 메시지의 버튼은 유지하여 재시도 가능.
                    slackNotifier.postThreadReply(channelId, messageTs,
                            String.format(":x: *%s* → %s 전환에 실패했습니다. Jira에서 직접 확인해주세요.",
                                    issueKey, statusLabel));
                }
            }
        } catch (Exception e) {
            log.error("Transition error for {} → {}: {}", issueKey, targetStatus, e.toString(), e);
            if (channelId != null && messageTs != null) {
                slackNotifier.postThreadReply(channelId, messageTs,
                        String.format(":x: *%s* 상태 변경 중 오류: %s", issueKey, e.getMessage()));
            }
        }
    }

    // STUDY: 전환 완료 후 원본 메시지의 버튼을 제거하고 결과 텍스트만 표시하는 Block Kit JSON 생성.
    static String buildCompletedBlocks(String issueKey, String statusEmoji,
                                               String statusLabel, String userName) {
        return String.format(
                "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"%s *%s* → %s (by %s)\"}}]",
                statusEmoji, escapeBlockJson(issueKey), escapeBlockJson(statusLabel),
                escapeBlockJson(userName));
    }

    private static String escapeBlockJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
