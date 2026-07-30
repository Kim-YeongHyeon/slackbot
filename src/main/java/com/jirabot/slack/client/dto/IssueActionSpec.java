package com.jirabot.slack.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;

/**
 * NL 이슈 조작 명령에서 추출된 구조화 액션 (prompts/skill-issue-action.md 의 JSON 계약).
 * <p>
 * STUDY: extract-then-execute — Sonnet 은 파싱만, 실행/검증(SP 스케일, 이슈 존재, 이름 해석)은 Java.
 *        compact constructor 정규화로 LLM 드리프트(미지 action, 범위 밖 confidence)를 파싱 실패가
 *        아닌 안전값으로 강등한다 (TaskQuerySpec 과 동일 설계).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueActionSpec(
        String action,        // assign|update_sp|update_summary|update_due|update_priority|sprint_move|link|unlink|list_links|subtask|none
        String issueKey,
        String otherKey,      // link/unlink 상대 키
        String assignee,      // assign 대상 (이름 또는 <@MENTION>)
        String value,         // update_* 값 (SP 숫자문자열/새 제목/날짜토큰/우선순위 버킷)
        String linkType,      // blocks|relates|duplicate
        String inwardKey,
        String outwardKey,
        Boolean directionConfident,
        String parentName,    // subtask 부모를 이름으로 지정한 경우
        String content,       // subtask 제목
        double confidence,
        String notes
) {
    private static final Set<String> ACTIONS = Set.of(
            "assign", "update_sp", "update_summary", "update_due", "update_priority",
            "sprint_move", "link", "unlink", "list_links", "subtask", "none");

    public IssueActionSpec {
        if (action == null || !ACTIONS.contains(action)) {
            action = "none";
        }
        if (directionConfident == null) {
            directionConfident = true;
        }
        if (confidence < 0) confidence = 0;
        if (confidence > 1) confidence = 1;
        issueKey = upperOrNull(issueKey);
        otherKey = upperOrNull(otherKey);
        inwardKey = upperOrNull(inwardKey);
        outwardKey = upperOrNull(outwardKey);
    }

    private static String upperOrNull(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase();
    }

    /** 추출 실패/파일 없음 폴백. */
    public static IssueActionSpec none() {
        return new IssueActionSpec("none", null, null, null, null, null, null, null,
                true, null, null, 0.0, null);
    }

    public boolean isActionable() {
        return !"none".equals(action) && confidence >= 0.6;
    }
}
