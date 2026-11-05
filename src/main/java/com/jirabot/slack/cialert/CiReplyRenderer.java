package com.jirabot.slack.cialert;

import java.util.Map;

// STUDY: 답글 형식은 Java 에서 고정한다. Claude 는 내용(JSON 필드)만 만들고, Slack 마크업·이스케이프는 여기서 처리 —
//        모델 출력에 <!channel> 이나 <url|가짜링크> 가 섞여도 그대로 렌더링되지 않는다.
public final class CiReplyRenderer {

    private static final int MAX_EVIDENCE_LINE = 300;
    private static final int MAX_TEXT = 600;

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "CODE", "🐛 코드 변경",
            "FLAKY", "🎲 불안정한 테스트",
            "INFRA", "🏗️ 인프라·러너",
            "CONFIG", "⚙️ CI 설정·시크릿",
            "DEPENDENCY", "📦 외부 의존성",
            "VULNERABILITY", "🛡️ 취약점(SBOM)",
            "UNKNOWN", "❓ 판단 어려움");

    private static final Map<String, String> CONFIDENCE_LABELS = Map.of(
            "high", "높음", "medium", "보통", "low", "낮음");

    private CiReplyRenderer() {}

    public static String analysis(String repo, String workflow, CiAnalysis a, String runStatus,
                                  String headSha, String author, String runUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 *CI 실패 분석* · ").append(escape(repo)).append(" · ").append(escape(workflow)).append('\n');
        sb.append("*분류* ").append(CATEGORY_LABELS.getOrDefault(a.category(), a.category()));
        String conf = CONFIDENCE_LABELS.get(a.confidence() == null ? "" : a.confidence().toLowerCase());
        if (conf != null) {
            sb.append(" (신뢰도 ").append(conf).append(')');
        }
        if (runStatus != null && !runStatus.isBlank()) {
            sb.append(" · ").append(escape(runStatus));
        }
        sb.append('\n');
        sb.append("*원인* ").append(escape(cut(a.summary(), MAX_TEXT))).append('\n');
        if (a.details() != null && !a.details().isBlank()) {
            sb.append(escape(cut(a.details(), MAX_TEXT))).append('\n');
        }
        if (!a.evidence().isEmpty()) {
            sb.append("*근거*\n");
            for (String e : a.evidence()) {
                sb.append("> `").append(escape(cut(e, MAX_EVIDENCE_LINE)).replace("`", "'")).append("`\n");
            }
        }
        if (!a.nextSteps().isEmpty()) {
            sb.append("*다음 조치*\n");
            for (String s : a.nextSteps()) {
                sb.append("• ").append(escape(cut(s, MAX_TEXT))).append('\n');
            }
        }
        sb.append('_').append("커밋 ").append(escape(CiFailureAnalyzer.shortSha(headSha)));
        if (author != null && !author.isBlank()) {
            sb.append(" · ").append(escape(author));
        }
        sb.append(" · <").append(runUrl).append("|run 보기>_");
        return sb.toString();
    }

    public static String repeat(String priorTimeKst, String permalink, String priorSummary) {
        String link = permalink == null || permalink.isBlank() ? "이전 분석" : "<" + permalink + "|이전 분석>";
        StringBuilder sb = new StringBuilder("🔁 ").append(priorTimeKst).append(" 알림과 같은 실패예요 → ").append(link);
        if (priorSummary != null && !priorSummary.isBlank()) {
            sb.append('\n').append("*원인* ").append(escape(cut(priorSummary, MAX_TEXT)));
        }
        return sb.toString();
    }

    public static String failure(String reason, String runUrl) {
        return "⚠️ 자동 분석에 실패했어요: " + escape(reason) + " — <" + runUrl + "|run 로그>를 직접 확인해주세요.";
    }

    // STUDY: Slack mrkdwn 의 제어 문자는 &, <, > 세 개뿐 — 이 셋만 이스케이프하면 멘션·링크 위조가 막힌다.
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.strip();
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
