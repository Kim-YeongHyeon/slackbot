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

    // STUDY: 채널이 nightly·벤치마크 채널과 합쳐져서(v0.0.95) 같은 알림 앱이 성공 결과·러너 대기 알림도 올린다.
    //        그 글에도 run 링크가 있으니, 실패 알림 워크플로 두 개의 제목 형식으로만 받는다.
    //          main-ci-failure-alert.yaml → ":rotating_light: *<workflow> failed on <branch>* (<org>/<repo>)"
    //          sbom-failure-alert.yaml    → ":rotating_light: *<what> failed on <ref>*" 또는 "…possible SBOM failure)*"
    //            <what> = "SBOM pipeline" | "Go checks" | "SBOM pipeline and Go checks" (envector 2026-11-09 #2779 부터 Go 검사 포함, v0.0.102)
    //        nightly 실패("*Nightly … failed on main*")는 뒤에 "(org/repo)" 가 없어서 걸러진다.
    private static final Pattern FAILURE_HEADLINE = Pattern.compile(
            "\\A\\s*:rotating_light: \\*(?:[^*\\n]+ failed on [^*\\n]+\\* \\([\\w.-]+/[\\w.-]+\\)"
                    + "|(?:SBOM pipeline(?: and Go checks)?|Go checks) failed on |[^*\\n]*possible SBOM failure)");

    private CiAlertParser() {}

    public static boolean isFailureAlert(String text) {
        return text != null && FAILURE_HEADLINE.matcher(text).find();
    }

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
