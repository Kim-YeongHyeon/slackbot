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
        String safe = rawText == null ? "" : rawText.strip();
        String title = safe.length() > 80 ? safe.substring(0, 80) : safe;
        if (title.isBlank()) {
            title = "Untitled issue from Slack";
        }
        return new IssueClassification(IssueType.OTHER, 3, title, safe);
    }
}
