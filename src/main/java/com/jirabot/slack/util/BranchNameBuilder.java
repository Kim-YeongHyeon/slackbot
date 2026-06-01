package com.jirabot.slack.util;

// STUDY: Jira 이슈 → 권장 git 브랜치명 생성. 실제 브랜치 생성은 Jira 개발 패널의 "브랜치 만들기"가
//        연결된 GitHub 로 위임해 수행한다(봇은 GitHub API 를 직접 호출하지 않는다). 봇은 규칙에 맞는
//        브랜치명을 "제안"하고, 사용자는 Jira UI 에서 대상 레포·base 를 선택해 생성한다.
//        명명 규칙(tasks/deferred/auto-branch-creation.md): Bug → bugfix/, 그 외 → feature/.
public final class BranchNameBuilder {

    private static final int MAX_SLUG_LEN = 50;

    private BranchNameBuilder() {}

    public static String build(String issueType, String issueKey, String summary) {
        String prefix = isBug(issueType) ? "bugfix/" : "feature/";
        String slug = slugify(summary);
        if (issueKey == null || issueKey.isBlank()) {
            return slug.isEmpty() ? prefix.substring(0, prefix.length() - 1) : prefix + slug;
        }
        return slug.isEmpty() ? prefix + issueKey : prefix + issueKey + "-" + slug;
    }

    // STUDY: Jira 이슈타입 name 은 한국어 사이트면 "버그"/"작업" 으로 저장된다(L4). 영어 "Bug" 도 함께 본다.
    static boolean isBug(String issueType) {
        if (issueType == null) {
            return false;
        }
        String t = issueType.toLowerCase();
        return t.contains("버그") || t.contains("bug");
    }

    // STUDY: 요약을 브랜치명에 안전한 슬러그로 변환. 영문 소문자/숫자/한글만 남기고 나머지는 하이픈으로,
    //        연속 하이픈 축약 + 양끝 트림 + 길이 제한. git 은 UTF-8 브랜치명을 허용하므로 한글은 보존한다.
    static String slugify(String summary) {
        if (summary == null) {
            return "";
        }
        String s = summary.toLowerCase().strip()
                .replaceAll("[^a-z0-9가-힣]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (s.length() > MAX_SLUG_LEN) {
            s = s.substring(0, MAX_SLUG_LEN).replaceAll("-+$", "");
        }
        return s;
    }
}
