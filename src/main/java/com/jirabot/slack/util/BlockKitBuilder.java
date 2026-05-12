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
    //        매 호출마다 new ObjectMapper() 할 필요 없다.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BlockKitBuilder() {}

    /**
     * 이슈 생성 완료 메시지용 Block Kit JSON을 생성한다.
     * Section(이슈 정보) + [Section(유사 이슈 경고)] + Divider + Actions(진행 중/완료 버튼)
     */
    public static String buildIssueCreatedBlocks(String key, String url,
                                                  IssueClassification classification,
                                                  List<IssueEntity> similar) {
        // STUDY: ArrayNode는 JSON 배열([...])을 표현. Block Kit의 blocks 필드는 배열이다.
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
        // STUDY: ObjectNode.put()은 문자열/숫자 등 단일 값을 설정.
        //        중첩 객체는 set() + createObjectNode()로 구성한다.
        ObjectNode divider = MAPPER.createObjectNode();
        divider.put("type", "divider");
        blocks.add(divider);

        // Actions: 진행 중 / 완료 버튼
        ObjectNode actions = MAPPER.createObjectNode();
        actions.put("type", "actions");
        ArrayNode elements = MAPPER.createArrayNode();

        // "진행 중" 버튼
        elements.add(buildButton("\ud83d\udd28 진행 중", "jira_transition_in_progress", key, null, null));

        // "완료" 버튼 (with confirm dialog)
        // STUDY: confirm 객체는 Slack Button의 확인 다이얼로그.
        //        사용자가 버튼을 클릭하면 confirm/deny 선택지가 먼저 표시된다.
        ObjectNode confirm = MAPPER.createObjectNode();
        confirm.set("title", plainText("확인"));
        confirm.set("text", mrkdwnText("정말 완료 처리하시겠습니까?"));
        confirm.set("confirm", plainText("완료"));
        confirm.set("deny", plainText("취소"));

        elements.add(buildButton("\u2705 완료", "jira_transition_done", key, "primary", confirm));

        actions.set("elements", elements);
        blocks.add(actions);

        try {
            return MAPPER.writeValueAsString(blocks);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Block Kit JSON", e);
        }
    }

    /**
     * 전환 완료 후 메시지 업데이트용 Block Kit JSON을 생성한다.
     * 원본 블록에서 actions 블록을 제거하고 결과 section을 추가한다.
     */
    public static String buildCompletedBlocks(String issueKey, String statusEmoji,
                                               String statusLabel, String userName,
                                               JsonNode originalBlocks) {
        ArrayNode result = MAPPER.createArrayNode();

        // STUDY: 원본 메시지의 블록(section, divider 등)을 보존하되
        //        actions 블록만 제거하여 버튼이 사라지게 한다.
        if (originalBlocks != null && originalBlocks.isArray()) {
            for (JsonNode block : originalBlocks) {
                String type = block.path("type").asText("");
                if (!"actions".equals(type)) {
                    result.add(block);
                }
            }
        }

        // 결과 section 추가
        String resultText = String.format(
                "%s *%s* \u2192 %s (by %s)", statusEmoji, issueKey, statusLabel, userName);
        result.add(buildMrkdwnSection(resultText));

        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Block Kit JSON", e);
        }
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
}
