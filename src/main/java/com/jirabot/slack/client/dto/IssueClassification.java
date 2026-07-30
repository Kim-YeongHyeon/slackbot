package com.jirabot.slack.client.dto;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record IssueClassification(
        IssueType type,
        int storyPoint,
        String title,
        String summary,
        // STUDY: "…를 X 에픽 아래에 만들어줘"의 에픽명 — Sonnet 스킬이 원문에서 추출(v0.0.66).
        //        키 해석은 Java(findEpicKeyByName). 언급 없으면 null. 정규식 추출(EPIC_BEFORE/AFTER) 대체.
        String parentEpicName
) {
    public enum IssueType { BUG, FEATURE, OTHER, EPIC }

    // 기존 4-인자 호출부(테스트/폴백)를 깨지 않는 위임 생성자. Jackson 은 canonical 로 바인딩.
    public IssueClassification(IssueType type, int storyPoint, String title, String summary) {
        this(type, storyPoint, title, summary, null);
    }

    // STUDY: 에픽은 컨테이너성 이슈라 Story Point 를 부여하지 않는다(0). Sonnet 이 만든 title/summary 는
    //        그대로 재사용하고 type 만 EPIC 으로 강제하여, 키워드 트리거 경로에서 분류 결과를 덮어쓴다.
    //        에픽 자신은 부모를 갖지 않으므로 parentEpicName 은 버린다.
    public IssueClassification asEpic() {
        return new IssueClassification(IssueType.EPIC, 0, title, summary, null);
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
