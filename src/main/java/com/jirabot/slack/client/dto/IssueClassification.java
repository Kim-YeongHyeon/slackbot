package com.jirabot.slack.client.dto;

public record IssueClassification(
        IssueType type,
        int storyPoint,
        String title,
        String summary
) {
    public enum IssueType { BUG, FEATURE, OTHER, EPIC }

    // STUDY: 에픽은 컨테이너성 이슈라 Story Point 를 부여하지 않는다(0). Sonnet 이 만든 title/summary 는
    //        그대로 재사용하고 type 만 EPIC 으로 강제하여, 키워드 트리거 경로에서 분류 결과를 덮어쓴다.
    public IssueClassification asEpic() {
        return new IssueClassification(IssueType.EPIC, 0, title, summary);
    }

    public static IssueClassification fallback(String rawText) {
        return fallback(rawText, IssueType.OTHER);
    }

    // STUDY: 분류 실패 시 type 은 Haiku 의도(register_bug→BUG 등)로 추정해 넘긴다 — OTHER 로 뭉뚱그리는 것보다 정확.
    public static IssueClassification fallback(String rawText, IssueType type) {
        String safe = rawText == null ? "" : rawText.strip();
        String title = cleanTitle(safe);
        if (title.length() > 80) {
            title = title.substring(0, 80).strip();
        }
        if (title.isBlank()) {
            title = "Untitled issue from Slack";
        }
        return new IssueClassification(type == null ? IssueType.OTHER : type, 3, title, safe);
    }

    // STUDY: Claude 분류 실패(타임아웃/에러/파싱실패) 시 원문이 제목이 되는데, 사용자가 붙인 명령·요청 어구
    //        ("티켓 만들어줘" 등)와 앞쪽 이슈 키([ES2-123])는 제목에 들어가면 안 된다 → 최소한으로 정제한다.
    static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw.strip();
        // STUDY: Jira summary 는 개행을 하드 거부(400 "개행 문자를 포함…"). 멀티라인 원문이 폴백 제목이
        //        될 때를 대비해 개행을 공백으로 접는다 (v0.0.64 실사고 수정).
        t = t.replaceAll("[\\r\\n]+", " ");
        // 앞쪽 이슈 키: "[ES2-123]" 또는 "ES2-123" (+뒤따르는 공백/콜론)
        t = t.replaceFirst("^\\[?[A-Za-z][A-Za-z0-9]*-\\d+\\]?[\\s:]*", "");
        // 뒤쪽 요청/명령 어구: "(이슈/티켓/버그/스토리 …) 만들어/생성/등록/추가/작성/올려 (해)(줘/주세요/…)"
        t = t.replaceAll(
                "[\\s,./]*((이슈|티켓|버그|스토리|이거|이것|이걸|좀)\\s*)*"
                + "(만들어|만들|생성|등록|추가|작성|올려)\\s*(해|해서)?\\s*(줘|주세요|줄래|주라|줭|줘요)?\\s*[.!~\\s]*$",
                "");
        return t.strip();
    }
}
