package com.jirabot.slack.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 명령형(prefix) 파싱 + NL 조작 명령의 공용 커맨드 타입.
 * <p>
 * STUDY: v0.0.65 아키텍처 — NL 조작("담당자를 X로", "SP 3으로", 링크, "…에 하위작업으로 …")의 파싱은
 *        정규식이 아니라 Haiku(issue_action) → Sonnet skill-issue-action 이 담당한다(표현 변형마다
 *        정규식 핫픽스가 반복되던 v0.0.61/64 문제의 구조적 해결). 이 클래스에는
 *        (1) 0초-지연 명령형 prefix(`하위작업 ES2-1 내용`)와
 *        (2) 스킬 추출 결과(IssueActionSpec)를 기존 실행부로 넘기는 커맨드 record 들만 남긴다.
 */
public final class IssueCommandParser {

    private IssueCommandParser() {}

    // STUDY: UNICODE_CHARACTER_CLASS — \p{L},\p{N} 가 한글/유니코드 문자·숫자까지 포함하도록.
    private static final int RX = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    // 명령형: "하위작업 ES2-123 로그인 리팩토링" — 라우팅 prefix 스테이지에서 즉시 실행.
    private static final Pattern SUBTASK_PREFIX_KEY = Pattern.compile(
            "^(?:하위\\s*작업|서브\\s*태스크|sub-?task|subtask)\\s+([A-Z][A-Z0-9]*-\\d+)\\s+(.+)$", RX);

    // 따옴표 콘텐츠: '…' "…" '…' "…" (여는/닫는 짝을 느슨하게 허용)
    private static final Pattern QUOTED = Pattern.compile("['\"‘“]([^'\"’”]+)['\"’”]");

    /** 팀 스토리포인트 스케일(docs/story-point-guide.md). 컨트롤러 SP 검증에서 사용. */
    public static final java.util.Set<Integer> VALID_STORY_POINTS = java.util.Set.of(1, 2, 3, 5, 8);

    // ==================== 커맨드 타입 (스킬 추출 결과 → 실행부 전달용) ====================

    /**
     * 하위작업 명령. parentKey 와 parentName 중 하나만 non-null.
     * content 는 하위작업 제목(추가 정제는 Sonnet classifyOnly 가 수행).
     */
    public record SubtaskCommand(String parentKey, String parentName, String content) {}

    /** 수정 대상 필드. */
    public enum UpdateField { STORY_POINT, SUMMARY, DUE_DATE, PRIORITY }

    /**
     * 필드 수정 명령. value 타입은 field 별로 다름:
     * STORY_POINT=Integer, SUMMARY=String(새 제목), PRIORITY=String(정규 버킷명),
     * DUE_DATE=String(날짜 토큰 — 컨트롤러가 KST 기준 ISO 날짜로 해석).
     */
    public record UpdateCommand(String key, UpdateField field, Object value) {}

    /** 지원하는 링크 관계. Jira 타입명 매핑은 컨트롤러(resolveLinkType)에서 수행. */
    public enum LinkRelation { BLOCKS, RELATES, DUPLICATE }

    /**
     * 이슈 링크 명령. Jira 방향 규칙 {@code inwardIssue <inward> outwardIssue} 기준.
     * ambiguous=true 면 컨트롤러가 방향 확인 버튼을 제시한다.
     */
    public record LinkCommand(String inwardKey, String outwardKey, LinkRelation relation, boolean ambiguous) {}

    // ==================== 명령형 prefix 파싱 (0초 지연 경로) ====================

    /**
     * 명령형 `하위작업 <KEY> <내용>` prefix 를 파싱한다. NL 형은 skill-issue-action 담당.
     */
    public static Optional<SubtaskCommand> parseSubtaskPrefix(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher pre = SUBTASK_PREFIX_KEY.matcher(text.strip());
        if (!pre.matches()) {
            return Optional.empty();
        }
        String content = extractContent(pre.group(2));
        if (content.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SubtaskCommand(pre.group(1).toUpperCase(), null, content));
    }

    /** 따옴표 콘텐츠 우선, 없으면 앞뒤 공백/구두점만 정리. */
    private static String extractContent(String text) {
        Matcher q = QUOTED.matcher(text);
        if (q.find()) {
            return q.group(1).strip();
        }
        return text.replaceAll("(?U)^[\\s,.!?~]+", "").replaceAll("(?U)[\\s,.!?~]+$", "").strip();
    }
}
