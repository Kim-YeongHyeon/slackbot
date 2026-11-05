package com.jirabot.slack.cialert;

import java.util.Collection;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// STUDY: 알림 본문에서 "어느 레포의 어느 run 인가"만 뽑는 순수 함수. 워크플로명·커밋·실패 step 은
//        본문 대신 GitHub API 응답을 기준으로 쓴다 — 본문 형식은 레포마다 조금씩 다르고 바뀔 수 있어서.
public final class CiAlertParser {

    // STUDY: Slack 은 URL 을 <url> 또는 <url|label> 로 감싼다. repo 세그먼트에서 '|', '>' 를 제외해
    //        마크업 안에서도 정확히 끊는다. (SlackEventController.GITHUB_PR_URL 과 같은 방식)
    private static final Pattern RUN_URL = Pattern.compile(
            "https://github\\.com/([\\w.-]+)/([\\w.-]+)/actions/runs/(\\d+)");

    // SBOM 알림: sbom-failure-alert.yaml 의 두 가지 제목, 또는 본문 끝의 /fix-sbom-cve 안내.
    private static final Pattern SBOM_MARKER = Pattern.compile(
            "SBOM pipeline failed|possible SBOM failure|/fix-sbom-cve");

    private CiAlertParser() {}

    public static Optional<CiAlert> parse(String text, String org, Collection<String> repos) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = RUN_URL.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        String owner = m.group(1);
        String repo = m.group(2);
        // STUDY: GitHub 레포 이름은 대소문자를 구분하지 않는다 (설정 "he-kms" ↔ 실제 "HE-KMS").
        //        알림 URL 의 표기를 그대로 써도 API 는 같은 레포로 응답한다.
        if (!owner.equalsIgnoreCase(org) || repos.stream().noneMatch(r -> r.equalsIgnoreCase(repo))) {
            return Optional.empty();
        }
        long runId = Long.parseLong(m.group(3));
        CiAlert.Kind kind = SBOM_MARKER.matcher(text).find() ? CiAlert.Kind.SBOM : CiAlert.Kind.CI;
        String runUrl = "https://github.com/" + owner + "/" + repo + "/actions/runs/" + runId;
        return Optional.of(new CiAlert(kind, repo, runId, runUrl, text));
    }
}
