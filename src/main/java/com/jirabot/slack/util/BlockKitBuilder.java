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
    public static final String ACTION_CREATE_BRANCH = "jira_create_branch";
    public static final String ACTION_LINK_CONFIRM = "jira_link_confirm";

    private BlockKitBuilder() {}

    /**
     * "진행 중" 전환 후 브랜치를 만들 repo 선택 버튼 블록을 생성한다.
     * Section(권장 브랜치명) + Actions(repo 버튼들). 버튼 value = "issueKey|repo|branch"
     * (issueKey/repo/슬러그에 '|' 가 없으므로 클릭 시 앞 2개 '|' 로 안전하게 분리).
     */
    public static String buildBranchRepoButtons(String issueKey, String branchName, List<String> repos) {
        ArrayNode blocks = MAPPER.createArrayNode();
        blocks.add(buildMrkdwnSection(
                ":herb: *브랜치 만들기* — 어느 repo에 만들까요?\n권장 브랜치명: `" + branchName + "`"));

        ArrayNode elements = MAPPER.createArrayNode();
        // STUDY: Slack 은 한 메시지 내 모든 버튼의 action_id 가 유일해야 한다(중복이면 invalid_blocks).
        //        repo 별로 인덱스를 붙여 고유화하되, 라우팅은 ACTION_CREATE_BRANCH prefix 로 판별한다.
        int i = 0;
        for (String repo : repos) {
            elements.add(buildButton(repo, ACTION_CREATE_BRANCH + "_" + i++,
                    issueKey + "|" + repo + "|" + branchName, null, null));
        }
        ObjectNode actions = MAPPER.createObjectNode();
        actions.put("type", "actions");
        actions.set("elements", elements);
        blocks.add(actions);

        return serialize(blocks);
    }

    /**
     * 링크 방향이 모호할 때 방향 확인 버튼 블록을 생성한다.
     * 버튼 value = "inwardKey|outwardKey|typeName" (클릭 시 그대로 linkIssues 에 전달).
     * action_id 는 ACTION_LINK_CONFIRM prefix + suffix 로 유일화(같은 메시지 내 중복 금지).
     *
     * @param verb 링크 타입의 outward 설명(예: "blocks") — 버튼 라벨 표기용
     */
    public static String buildLinkConfirmButtons(String keyA, String keyB, String verb, String typeName) {
        ArrayNode blocks = MAPPER.createArrayNode();
        blocks.add(buildMrkdwnSection(String.format(
                ":link: *%s* 와(과) *%s* 의 링크 방향을 확인해주세요.", keyA, keyB)));

        ArrayNode elements = MAPPER.createArrayNode();
        // 옵션 1: A verb B → outward=A(동작 주체), inward=B → value = "inward|outward|type" = "B|A|type"
        elements.add(buildButton(String.format("%s %s %s", keyA, verb, keyB),
                ACTION_LINK_CONFIRM + "_1", keyB + "|" + keyA + "|" + typeName, "primary", null));
        // 옵션 2: B verb A → outward=B, inward=A → value = "A|B|type"
        elements.add(buildButton(String.format("%s %s %s", keyB, verb, keyA),
                ACTION_LINK_CONFIRM + "_2", keyA + "|" + keyB + "|" + typeName, null, null));
        elements.add(buildButton("취소", ACTION_LINK_CONFIRM + "_cancel", "cancel", null, null));

        ObjectNode actions = MAPPER.createObjectNode();
        actions.put("type", "actions");
        actions.set("elements", elements);
        blocks.add(actions);

        return serialize(blocks);
    }

    /**
     * 이슈 키 조회 카드 Block Kit JSON 을 생성한다 (공식 Jira-Slack 앱의 /jira KEY 카드 대응).
     * Section(제목+링크) + Section(fields 2열: 유형/상태/담당자/보고자/SP/스프린트)
     * + [Section(설명 일부)] + 상태별 다음 단계 버튼(완료 이슈는 버튼 없음).
     * 버튼은 기존 전환 action_id 재사용 — SlackInteractionController.handleTransition 이 그대로 처리한다.
     *
     * @param statusCategory 한국어 상태 카테고리 ("해야 할 일"/"진행 중"/"완료"), 그 외/null 은 버튼 없음
     */
    public static String buildIssueCardBlocks(String key, String url, String summary,
                                              String issueType, String status, String statusCategory,
                                              String assignee, String reporter,
                                              Double storyPoint, String sprintName,
                                              String description) {
        ArrayNode blocks = MAPPER.createArrayNode();

        blocks.add(buildMrkdwnSection(String.format(":card_index: *<%s|[%s] %s>*", url, key, summary)));

        // STUDY: section.fields — Slack 이 2열 그리드로 렌더링한다 (최대 10개).
        ObjectNode fieldsSection = MAPPER.createObjectNode();
        fieldsSection.put("type", "section");
        ArrayNode fields = MAPPER.createArrayNode();
        fields.add(mrkdwnText("*유형*\n" + orDash(issueType)));
        fields.add(mrkdwnText("*상태*\n" + orDash(status)));
        fields.add(mrkdwnText("*담당자*\n" + orDash(assignee)));
        fields.add(mrkdwnText("*보고자*\n" + orDash(reporter)));
        fields.add(mrkdwnText("*Story Point*\n"
                + (storyPoint == null || storyPoint == 0 ? "-" : String.valueOf(storyPoint.intValue()))));
        fields.add(mrkdwnText("*스프린트*\n" + orDash(sprintName)));
        fieldsSection.set("fields", fields);
        blocks.add(fieldsSection);

        if (description != null && !description.isBlank()) {
            String snippet = description.length() > 200 ? description.substring(0, 200) + "…" : description;
            blocks.add(buildMrkdwnSection("*설명*\n" + snippet));
        }

        // 상태 카테고리에 맞는 다음 단계 버튼 (완료/미상은 버튼 없음)
        ArrayNode elements = MAPPER.createArrayNode();
        if ("해야 할 일".equals(statusCategory)) {
            elements.add(buildButton("🔨 진행 중", ACTION_IN_PROGRESS, key, null, null));
            elements.add(buildButton("⚡ 바로 완료", ACTION_QUICK_DONE, key, "primary",
                    buildConfirm("확인", "해야 할 일 → 진행 중 → 완료를 한번에 처리합니다. 계속하시겠습니까?", "실행", "취소")));
        } else if ("진행 중".equals(statusCategory)) {
            elements.add(buildButton("🔍 검토 중", ACTION_IN_REVIEW, key, null, null));
            elements.add(buildButton("✅ 완료", ACTION_DONE, key, "primary",
                    buildConfirm("확인", "정말 완료 처리하시겠습니까?", "완료", "취소")));
        }
        if (!elements.isEmpty()) {
            ObjectNode actions = MAPPER.createObjectNode();
            actions.put("type", "actions");
            actions.set("elements", elements);
            blocks.add(actions);
        }

        return serialize(blocks);
    }

    private static String orDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    /**
     * 이슈 생성 완료 메시지용 Block Kit JSON을 생성한다.
     * Section(이슈 정보) + [Section(유사 이슈 경고)] + Divider + Actions(해야 할 일/진행 중/바로 완료 버튼)
     */
    public static String buildIssueCreatedBlocks(String key, String url,
                                                  IssueClassification classification,
                                                  List<IssueEntity> similar) {
        return buildIssueCreatedBlocks(key, url, classification, similar, -1);
    }

    /**
     * @param elapsedMs Slack 메시지 수신부터의 응답 소요시간(ms). 0 이하면 표기 생략.
     */
    public static String buildIssueCreatedBlocks(String key, String url,
                                                  IssueClassification classification,
                                                  List<IssueEntity> similar, long elapsedMs) {
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

        appendElapsedContext(blocks, elapsedMs);

        return serialize(blocks);
    }

    /**
     * 에픽 생성 완료 메시지용 Block Kit JSON을 생성한다.
     * 에픽은 스프린트 워크플로(해야 할 일/진행 중/완료) 대상이 아니므로 액션 버튼 없이
     * 정보 Section(+유사 이슈 경고)만 표시하여 일반 스토리/버그 알림과 시각적으로 구별한다.
     */
    public static String buildEpicCreatedBlocks(String key, String url,
                                                IssueClassification classification,
                                                List<IssueEntity> similar) {
        return buildEpicCreatedBlocks(key, url, classification, similar, -1);
    }

    /**
     * @param elapsedMs Slack 메시지 수신부터의 응답 소요시간(ms). 0 이하면 표기 생략.
     */
    public static String buildEpicCreatedBlocks(String key, String url,
                                                IssueClassification classification,
                                                List<IssueEntity> similar, long elapsedMs) {
        ArrayNode blocks = MAPPER.createArrayNode();

        String sectionText = String.format(
                ":bookmark_tabs: *Epic이 생성되었습니다!*\n*<%s|[%s] %s>*\n분류: EPIC",
                url, key, classification.title());
        blocks.add(buildMrkdwnSection(sectionText));

        if (similar != null && !similar.isEmpty()) {
            StringBuilder warning = new StringBuilder(":warning: *유사한 이슈가 존재합니다:*");
            for (IssueEntity s : similar) {
                warning.append("\n  • ")
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

        appendElapsedContext(blocks, elapsedMs);

        return serialize(blocks);
    }

    // STUDY: context 블록 — section 보다 작은 회색 보조 텍스트로 렌더링된다.
    //        응답 소요시간을 메시지 맨 끝에 표기 (원인 파악/최적화용, response_metrics 와 동일 기준).
    private static void appendElapsedContext(ArrayNode blocks, long elapsedMs) {
        if (elapsedMs <= 0) {
            return;
        }
        ObjectNode context = MAPPER.createObjectNode();
        context.put("type", "context");
        ArrayNode elements = MAPPER.createArrayNode();
        elements.add(mrkdwnText(String.format(":stopwatch: 응답 시간 %.1f초", elapsedMs / 1000.0)));
        context.set("elements", elements);
        blocks.add(context);
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
