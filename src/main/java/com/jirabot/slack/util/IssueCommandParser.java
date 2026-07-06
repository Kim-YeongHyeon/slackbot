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
