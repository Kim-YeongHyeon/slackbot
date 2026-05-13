package com.jirabot.slack.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.entity.IssueEntity;
import java.util.List;

// STUDY: Jackson ObjectMapper로 Block Kit JSON을 구조적으로 생성한다.
//        StringBuilder + 수동 escape 대신 ObjectNode/ArrayNode를 사용하면
//        JSON 특수문자 이스케이프가 자동으로 처리되어 injection/파싱 오류를 방지한다.
public final class BlockKitBuilder {

    // STUDY: ObjectMapper는 thread-safe하므로 static 필드로 재사용 가능.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 버튼 action_id 상수
    public static final String ACTION_TODO = "jira_transition_todo";
    public static final String ACTION_IN_PROGRESS = "jira_transition_in_progress";
    public static final String ACTION_IN_REVIEW = "jira_transition_in_review";
    public static final String ACTION_DONE = "jira_transition_done";
    public static final String ACTION_QUICK_DONE = "jira_quick_done";

    private BlockKitBuilder() {}

    /**
     * 이슈 생성 완료 메시지용 Block Kit JSON을 생성한다.
     * Section(이슈 정보) + [Section(유사 이슈 경고)] + Divider + Actions(해야 할 일/진행 중/바로 완료 버튼)
     */
    public static String buildIssueCreatedBlocks(String key, String url,
                                                  IssueClassification classification,
                                                  List<IssueEntity> similar) {
        ArrayNode blocks = MAPPER.createArrayNode();

        // Section: 이슈 정보
        String sectionText = String.format(
                ":white_check_mark: Jira 이슈가 등록되었습니다!\n*<%s|[%s] %s>*\n분류: %s | Story Point: %d",
                url, key, classification.title(), classification.type(),
                classification.storyPoint());
        blocks.add(buildMrkdwnSection(sectionText));

        // Similar issues warning (optional)
        if (similar != null && !similar.isEmpty()) {
            StringBuilder warning = new StringBuilder(":warning: *유사한 이슈가 존재합니다:*");
            for (IssueEntity s : similar) {
                warning.append("\n  \u2022 ")
                        .append(s.getIssueKey())
                        .append(" ")
                        .append(s.getSummary())
                        .append(" (")
                        .append(s.getStatus())
                        .append(")");
            }
            warning.append("\n중복이라면 새 이슈를 닫아주세요.");
            blocks.add(buildMrkdwnSection(warning.toString()));
        }

        // Divider
        ObjectNode divider = MAPPER.createObjectNode();
        divider.put("type", "divider");
        blocks.add(divider);

        // STUDY: 이슈 생성 직후 상태는 Backlog. 버튼 흐름:
        //        해야 할 일(Backlog→ToDo) → 진행 중(ToDo→InProgress+Sprint) → 검토 중 → 완료
        //        "바로 완료"는 한번에 Backlog→ToDo→InProgress→Done + Sprint 이동까지 처리.
        ArrayNode elements = MAPPER.createArrayNode();
        elements.add(buildButton("\ud83d\udccb 해야 할 일", ACTION_TODO, key, null, null));
        elements.add(buildButton("\ud83d\udd28 진행 중", ACTION_IN_PROGRESS, key, null, null));
        elements.add(buildButton("\u26a1 바로 완료", ACTION_QUICK_DONE, key, "primary",
                buildConfirm("확인", "해야 할 일 → 진행 중 → 완료를 한번에 처리합니다. 계속하시겠습니까?", "실행", "취소")));

        ObjectNode actions = MAPPER.createObjectNode();
        actions.put("type", "actions");
        actions.set("elements", elements);
        blocks.add(actions);

        return serialize(blocks);
    }

    /**
     * 상태 전환 후 다음 단계 버튼을 포함하는 Block Kit JSON을 생성한다.
     * 원본 블록에서 actions를 제거하고, 결과 section + 다음 단계 actions를 추가한다.
     */
    public static String buildTransitionedBlocks(String issueKey, String statusEmoji,
                                                  String statusLabel, String userName,
                                                  String nextActionId, String nextLabel,
                                                  JsonNode originalBlocks) {
        ArrayNode result = MAPPER.createArrayNode();

        if (originalBlocks != null && originalBlocks.isArray()) {
            for (JsonNode block : originalBlocks) {
                if (!"actions".equals(block.path("type").asText(""))) {
                    result.add(block);
                }
            }
        }

        // 결과 section
        String resultText = String.format(
                "%s *%s* \u2192 %s (by %s)", statusEmoji, issueKey, statusLabel, userName);
        result.add(buildMrkdwnSection(resultText));

        // 다음 단계 버튼이 있으면 추가
        if (nextActionId != null) {
            ArrayNode elements = MAPPER.createArrayNode();
            ObjectNode confirm = null;
            String style = null;
            if (ACTION_DONE.equals(nextActionId)) {
                confirm = buildConfirm("확인", "정말 완료 처리하시겠습니까?", "완료", "취소");
                style = "primary";
            }
            elements.add(buildButton(nextLabel, nextActionId, issueKey, style, confirm));
            ObjectNode actions = MAPPER.createObjectNode();
            actions.put("type", "actions");
            actions.set("elements", elements);
            result.add(actions);
        }

        return serialize(result);
    }

    /**
     * 최종 완료 후 메시지 업데이트용 Block Kit JSON을 생성한다.
     * 원본 블록에서 actions를 제거하고 결과 section만 추가 (버튼 없음).
     */
    public static String buildCompletedBlocks(String issueKey, String statusEmoji,
                                               String statusLabel, String userName,
                                               JsonNode originalBlocks) {
        return buildTransitionedBlocks(issueKey, statusEmoji, statusLabel, userName,
                null, null, originalBlocks);
    }

    private static ObjectNode buildMrkdwnSection(String text) {
        ObjectNode section = MAPPER.createObjectNode();
        section.put("type", "section");
        section.set("text", mrkdwnText(text));
        return section;
    }

    private static ObjectNode buildButton(String label, String actionId, String value,
                                           String style, ObjectNode confirm) {
        ObjectNode button = MAPPER.createObjectNode();
        button.put("type", "button");
        button.set("text", plainText(label));
        button.put("action_id", actionId);
        button.put("value", value);
        if (style != null) {
            button.put("style", style);
        }
        if (confirm != null) {
            button.set("confirm", confirm);
        }
        return button;
    }

    private static ObjectNode buildConfirm(String title, String text, String confirmLabel, String denyLabel) {
        ObjectNode confirm = MAPPER.createObjectNode();
        confirm.set("title", plainText(title));
        confirm.set("text", mrkdwnText(text));
        confirm.set("confirm", plainText(confirmLabel));
        confirm.set("deny", plainText(denyLabel));
        return confirm;
    }

    private static ObjectNode plainText(String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "plain_text");
        node.put("text", text);
        return node;
    }

    private static ObjectNode mrkdwnText(String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "mrkdwn");
        node.put("text", text);
        return node;
    }

    private static String serialize(ArrayNode blocks) {
        try {
            return MAPPER.writeValueAsString(blocks);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Block Kit JSON", e);
        }
    }
}
