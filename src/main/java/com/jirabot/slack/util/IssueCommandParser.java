package com.jirabot.slack.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이슈 키/이름이 포함된 결정적(deterministic) 명령을 파싱한다.
 * <p>
 * STUDY: 컨트롤러 비대화를 막고 파싱 로직을 단위 테스트하기 위한 순수-정적 유틸.
 *        SlackEventController.extractCardIssueKey/containsEpicKeyword 와 같은 패턴.
 *        Haiku/Sonnet 을 거치지 않고(0초 지연), 실패 시 null 을 반환해 기존 흐름으로 폴스루한다.
 */
public final class IssueCommandParser {

    private IssueCommandParser() {}

    // STUDY: UNICODE_CHARACTER_CLASS — \p{L},\p{N} 가 한글/유니코드 문자·숫자까지 포함하도록.
    private static final int RX = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9]*-\\d+");

    // 하위작업 키워드: 하위작업 / 하위 작업 / 서브태스크 / 서브 태스크 / subtask / sub-task.
    private static final Pattern SUBTASK_KEYWORD = Pattern.compile(
            "(?<![가-힣A-Za-z])(하위\\s*작업|서브\\s*태스크|sub-?task)", RX);

    // 명령형: "하위작업 ES2-123 로그인 리팩토링" — 라우팅 1차(prefix)에서 쓴다.
    private static final Pattern SUBTASK_PREFIX_KEY = Pattern.compile(
            "^(?:하위\\s*작업|서브\\s*태스크|sub-?task|subtask)\\s+([A-Z][A-Z0-9]*-\\d+)\\s+(.+)$", RX);

    // 이름형: "<이름> (스토리|이슈|티켓)? 아래|밑|하위(에) ... 하위작업" — EPIC_BEFORE 스타일.
    private static final Pattern PARENT_NAME_BEFORE = Pattern.compile(
            "([\\p{L}\\p{N}][\\p{L}\\p{N} .+_/\\-]*?)\\s*(?:스토리|story|이슈|issue|티켓|ticket)?\\s*"
                    + "(?:아래|밑|하위)에?", RX);

    // 따옴표 콘텐츠: '…' "…" ‘…’ “…” (여는/닫는 짝을 느슨하게 허용)
    private static final Pattern QUOTED = Pattern.compile("['\"‘“]([^'\"’”]+)['\"’”]");

    // 명령 어미 제거용: "... 추가해줘/생성해줘/만들어줘/등록해줘" 및 조사 (으로|로)
    private static final Pattern SUBTASK_TAIL = Pattern.compile(
            "\\s*(?:으?로)?\\s*(?:하위\\s*작업|서브\\s*태스크|sub-?task|subtask)?\\s*"
                    + "(?:추가|생성|만들|등록)\\S*\\s*$", RX);

    /**
     * 하위작업 명령. parentKey 와 parentName 중 정확히 하나만 non-null.
     * content 는 하위작업 제목(추가 정제는 Sonnet classifyOnly 가 수행).
     */
    public record SubtaskCommand(String parentKey, String parentName, String content) {}

    /**
     * 텍스트가 "특정 부모 이슈 아래 하위작업 생성" 명령이면 파싱한다.
     * 스레드 밖에서 키/이름으로 부모를 지정하는 경우를 잡는다.
     *
     * <p>매칭 규칙:
     * <ul>
     *   <li>하위작업 키워드가 없으면 → empty (가로채지 않음)</li>
     *   <li>이슈 키가 정확히 1개 → 그 키가 부모</li>
     *   <li>이슈 키가 없고 "&lt;이름&gt; 아래 ... 하위작업" 형태 → 이름이 부모</li>
     *   <li>이슈 키 2개 이상 → empty (모호)</li>
     * </ul>
     */
    public static Optional<SubtaskCommand> parseSubtask(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();

        // 명령형 prefix: "하위작업 ES2-123 <내용>"
        Matcher pre = SUBTASK_PREFIX_KEY.matcher(t);
        if (pre.matches()) {
            String content = extractContent(pre.group(2), pre.group(1), null);
            if (!content.isBlank()) {
                return Optional.of(new SubtaskCommand(pre.group(1).toUpperCase(), null, content));
            }
        }

        if (!SUBTASK_KEYWORD.matcher(t).find()) {
            return Optional.empty();
        }

        // 이슈 키 개수 확인
        Matcher km = ISSUE_KEY.matcher(t);
        String firstKey = null;
        int keyCount = 0;
        while (km.find()) {
            if (firstKey == null) firstKey = km.group();
            keyCount++;
        }
        if (keyCount >= 2) {
            return Optional.empty(); // 부모가 모호 — 가로채지 않는다.
        }

        if (keyCount == 1) {
            String content = extractContent(t, firstKey, null);
            if (content.isBlank()) {
                // 내용 없이 "ES2-123 하위작업" 만 → content 없음(호출부가 사용법 안내)
                return Optional.of(new SubtaskCommand(firstKey.toUpperCase(), null, ""));
            }
            return Optional.of(new SubtaskCommand(firstKey.toUpperCase(), null, content));
        }

        // 이름형: "<이름> 아래 ... 하위작업"
        Matcher nm = PARENT_NAME_BEFORE.matcher(t);
        if (nm.find()) {
            String name = nm.group(1).strip();
            if (!name.isEmpty()) {
                String content = extractContent(t, null, name);
                return Optional.of(new SubtaskCommand(null, name, content));
            }
        }
        return Optional.empty();
    }

    // ==================== 이슈 링크 ====================

    /** 지원하는 링크 관계. Jira 타입명 매핑은 클라이언트에서 수행. */
    public enum LinkRelation { BLOCKS, RELATES, DUPLICATE }

    /**
     * 이슈 링크 명령. Jira 방향 규칙 {@code inwardIssue <inward> outwardIssue} 기준으로
     * inwardKey/outwardKey 를 채운다. 방향 판별이 불가하면 ambiguous=true (호출부가 확인 버튼 제시).
     */
    public record LinkCommand(String inwardKey, String outwardKey, LinkRelation relation, boolean ambiguous) {}

    private static final Pattern DUP_KW = Pattern.compile("(?i)(중복|duplicate)");
    private static final Pattern BLOCK_KW = Pattern.compile("(?i)(block|블락|블록|막)");
    private static final Pattern DEPEND_KW = Pattern.compile("(?i)(의존|depend)");
    // RELATES 는 명시적 연결 의도(관련/연관 단독은 오탐이 많아 제외).
    private static final Pattern RELATES_KW = Pattern.compile("(?i)(relate|연결|링크|\\blink\\b)");
    // 링크 해제 동사 — 링크 생성보다 먼저 확인해야 한다(Phase 4 에서 처리).
    private static final Pattern UNLINK_KW = Pattern.compile("(?i)(해제|삭제|제거|끊|unlink|remove)");

    // 피동(A가 B에 막힘 → A is blocked by B): inward=A, outward=B
    private static final Pattern PASSIVE = Pattern.compile(
            "(?i)(막혀|막힌|막힘|막혔|블락\\s*(되|당)|블록\\s*(되|당)|block\\s*(되|당)|blocked\\s+by|"
                    + "때문에|에\\s*의해|의존|depend)");
    // 능동(A가 B를 막음 → A blocks B): inward=B, outward=A
    private static final Pattern ACTIVE = Pattern.compile(
            "(?i)((를|을)\\s*(?:block|블락|블록|막)|\\bblocks\\b)");

    /** 링크 해제 명령인지(키 2개 + 해제 동사 + 링크/연결 언급). Phase 4 전까지 "지원 예정" 응답용. */
    public static boolean isUnlink(String text) {
        if (text == null) return false;
        return UNLINK_KW.matcher(text).find()
                && (RELATES_KW.matcher(text).find() || text.toLowerCase().contains("링크"))
                && twoKeys(text) != null;
    }

    /**
     * 텍스트가 두 이슈 링크 명령이면 파싱한다. 정확히 이슈 키 2개 + 관계 동사가 있어야 한다.
     */
    public static Optional<LinkCommand> parseLink(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();
        String[] keys = twoKeys(t);
        if (keys == null) {
            return Optional.empty(); // 정확히 2개가 아니면 링크 명령 아님
        }
        String a = keys[0], b = keys[1];

        boolean dup = DUP_KW.matcher(t).find();
        boolean block = BLOCK_KW.matcher(t).find();
        boolean depend = DEPEND_KW.matcher(t).find();
        boolean relates = RELATES_KW.matcher(t).find();

        if (!dup && !block && !depend && !relates) {
            return Optional.empty(); // 관계 동사 없음
        }

        // 1) Duplicate 우선 — "A duplicates B" → outward=A(duplicates), inward=B
        if (dup) {
            return Optional.of(new LinkCommand(b, a, LinkRelation.DUPLICATE, false));
        }

        // 2) Blocks / depends — 방향 판별
        if (block || depend) {
            boolean passive = PASSIVE.matcher(t).find();
            boolean active = ACTIVE.matcher(t).find();
            if (passive && !active) {
                // A is blocked by B → inward=A, outward=B
                return Optional.of(new LinkCommand(a, b, LinkRelation.BLOCKS, false));
            }
            if (active && !passive) {
                // A blocks B → inward=B, outward=A
                return Optional.of(new LinkCommand(b, a, LinkRelation.BLOCKS, false));
            }
            // 방향 모호 → 확인 버튼(기본 표기는 a,b 순서)
            return Optional.of(new LinkCommand(a, b, LinkRelation.BLOCKS, true));
        }

        // 3) Relates — 방향 무관
        return Optional.of(new LinkCommand(a, b, LinkRelation.RELATES, false));
    }

    /** 정확히 2개의 이슈 키가 있으면 [첫째, 둘째] 대문자 배열, 아니면 null. */
    private static String[] twoKeys(String t) {
        Matcher km = ISSUE_KEY.matcher(t);
        String a = null, b = null;
        int count = 0;
        while (km.find()) {
            count++;
            if (a == null) a = km.group();
            else if (b == null) b = km.group();
        }
        if (count != 2) return null;
        return new String[]{a.toUpperCase(), b.toUpperCase()};
    }

    // ==================== 필드 수정 / 스프린트 이동 ====================

    /** 수정 대상 필드. */
    public enum UpdateField { STORY_POINT, SUMMARY, DUE_DATE, PRIORITY }

    /**
     * 필드 수정 명령. value 타입은 field 별로 다름:
     * STORY_POINT=Integer, SUMMARY=String(새 제목), PRIORITY=String(정규 버킷명 Highest/High/…),
     * DUE_DATE=String(원문 토큰 — 컨트롤러가 KST 기준으로 ISO 날짜로 해석).
     */
    public record UpdateCommand(String key, UpdateField field, Object value) {}

    private static final Pattern SP_KW = Pattern.compile(
            "(?i)(sp|스토리\\s*포인트|story\\s*points?|포인트)");
    private static final Pattern SP_AFTER_KW = Pattern.compile(
            "(?i)(?:sp|스토리\\s*포인트|story\\s*points?|포인트)\\D{0,6}(\\d+)");
    private static final Pattern SP_BEFORE_UNIT = Pattern.compile("(\\d+)\\s*점");
    private static final Pattern SUMMARY_KW = Pattern.compile("(?i)(제목|타이틀|summary)");
    private static final Pattern DUE_KW = Pattern.compile("(?i)(마감|기한|due)");
    private static final Pattern PRIORITY_KW = Pattern.compile("(?i)(우선\\s*순위|priority)");

    // 절대 날짜: yyyy.MM.dd / yyyy-MM-dd / MM.dd / MM/dd
    private static final Pattern ABS_DATE_FULL = Pattern.compile("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");
    private static final Pattern ABS_DATE_MD = Pattern.compile("(?<!\\d)(\\d{1,2})[.\\-/](\\d{1,2})(?!\\d)");

    /** 팀 스토리포인트 스케일(docs/story-point-guide.md). */
    public static final java.util.Set<Integer> VALID_STORY_POINTS = java.util.Set.of(1, 2, 3, 5, 8);

    /**
     * "ES2-123 SP 3으로 / 제목을 '..'로 / 마감일 금요일로 / 우선순위 높음" 형태의 단일 이슈 필드 수정 파싱.
     * 이슈 키가 정확히 1개이고 필드 키워드가 있을 때만 발동.
     */
    public static Optional<UpdateCommand> parseUpdate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();
        String key = oneKey(t);
        if (key == null) {
            return Optional.empty();
        }
        // 키를 제거해 키의 숫자(-123)를 값으로 오인하지 않도록.
        String rest = t.replace(key, " ").replace(key.toLowerCase(), " ");

        // 1) 제목 변경 — 따옴표 새 제목 필수
        if (SUMMARY_KW.matcher(rest).find()) {
            Matcher q = QUOTED.matcher(t);
            if (q.find()) {
                return Optional.of(new UpdateCommand(key, UpdateField.SUMMARY, q.group(1).strip()));
            }
            // 따옴표 없으면 값 모호 → SUMMARY 지정하되 value=null (컨트롤러가 사용법 안내)
            return Optional.of(new UpdateCommand(key, UpdateField.SUMMARY, null));
        }

        // 2) Story Point
        if (SP_KW.matcher(rest).find()) {
            Integer sp = extractStoryPoint(rest);
            return Optional.of(new UpdateCommand(key, UpdateField.STORY_POINT, sp)); // sp=null → 컨트롤러가 안내
        }

        // 3) 마감일
        if (DUE_KW.matcher(rest).find()) {
            String token = extractDueToken(rest);
            return Optional.of(new UpdateCommand(key, UpdateField.DUE_DATE, token)); // token=null → 안내
        }

        // 4) 우선순위
        if (PRIORITY_KW.matcher(rest).find()) {
            String bucket = extractPriorityBucket(rest);
            return Optional.of(new UpdateCommand(key, UpdateField.PRIORITY, bucket)); // bucket=null → 안내
        }

        return Optional.empty();
    }

    private static Integer extractStoryPoint(String rest) {
        Matcher m = SP_AFTER_KW.matcher(rest);
        if (m.find()) {
            return safeInt(m.group(1));
        }
        Matcher u = SP_BEFORE_UNIT.matcher(rest);
        if (u.find()) {
            return safeInt(u.group(1));
        }
        return null;
    }

    private static Integer safeInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 마감일 원문 토큰 추출(절대/상대/요일). 컨트롤러가 KST 기준으로 ISO 날짜 해석.
    private static String extractDueToken(String rest) {
        Matcher full = ABS_DATE_FULL.matcher(rest);
        if (full.find()) {
            return full.group();
        }
        Matcher rel = Pattern.compile("(오늘|내일|모레|글피|다음\\s*주|"
                + "월요일|화요일|수요일|목요일|금요일|토요일|일요일|"
                + "monday|tuesday|wednesday|thursday|friday|saturday|sunday)",
                Pattern.CASE_INSENSITIVE).matcher(rest);
        if (rel.find()) {
            return rel.group();
        }
        Matcher md = ABS_DATE_MD.matcher(rest);
        if (md.find()) {
            return md.group();
        }
        return null;
    }

    private static String extractPriorityBucket(String rest) {
        String r = rest.toLowerCase();
        if (r.matches(".*(긴급|최고|가장\\s*높|매우\\s*높|highest).*")) return "Highest";
        if (r.matches(".*(최저|가장\\s*낮|매우\\s*낮|lowest).*")) return "Lowest";
        if (r.matches(".*(높|high).*")) return "High";
        if (r.matches(".*(낮|low).*")) return "Low";
        if (r.matches(".*(보통|중간|medium|normal).*")) return "Medium";
        return null;
    }

    private static final Pattern SPRINT_MOVE = Pattern.compile(
            "(?i)스프린트\\s*(?:로|에|으로)?\\s*(?:옮겨|이동|넣어|추가|올려|보내)");

    /**
     * "ES2-123 스프린트로 옮겨줘" 형태의 활성 스프린트 이동 명령. 이슈 키가 정확히 1개일 때만.
     */
    public static Optional<String> parseSprintMove(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String key = oneKey(text);
        if (key == null) {
            return Optional.empty();
        }
        return SPRINT_MOVE.matcher(text).find() ? Optional.of(key) : Optional.empty();
    }

    /** 이슈 키가 정확히 1개면 그 키(대문자), 아니면 null. */
    private static String oneKey(String t) {
        Matcher km = ISSUE_KEY.matcher(t);
        String key = null;
        int count = 0;
        while (km.find()) {
            count++;
            if (key == null) key = km.group();
        }
        return count == 1 ? key.toUpperCase() : null;
    }

    /**
     * 하위작업 제목 추출: 따옴표 콘텐츠 우선, 없으면 키/이름/키워드/명령어미를 제거한 나머지.
     */
    private static String extractContent(String text, String key, String parentName) {
        Matcher q = QUOTED.matcher(text);
        if (q.find()) {
            return q.group(1).strip();
        }
        String s = text;
        if (key != null) {
            s = s.replace(key, " ");
        }
        if (parentName != null) {
            // "<이름> 아래" 구절 통째로 제거
            s = PARENT_NAME_BEFORE.matcher(s).replaceFirst(" ");
        }
        s = SUBTASK_KEYWORD.matcher(s).replaceAll(" ");
        // 조사/전치: "...에 / ...에다 / under" 제거 (부모 지정 잔여)
        s = s.replaceAll("(?i)\\bunder\\b", " ");
        s = SUBTASK_TAIL.matcher(s).replaceFirst(" ");
        // STUDY: 앞뒤 공백·구두점만 정리한다. 한글 조사(에/로/를…)까지 벗기면 "로그인" 같은 실제 내용이
        //        깎이므로 제거하지 않는다 — 남은 조사는 Sonnet classifyOnly 가 제목화하며 정제한다.
        s = s.replaceAll("(?U)^[\\s,.!?~]+", "")
             .replaceAll("(?U)[\\s,.!?~]+$", "");
        return s.strip();
    }
}
