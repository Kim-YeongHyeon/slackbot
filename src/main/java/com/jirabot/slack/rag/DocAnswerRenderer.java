package com.jirabot.slack.rag;

import static com.jirabot.slack.cialert.CiReplyRenderer.escape;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 문서 질문의 Slack 답글(mrkdwn)을 만든다. */
public final class DocAnswerRenderer {

    static final int MAX_ANSWER_CHARS = 3500;
    static final int MAX_SOURCES = 5;
    static final int FALLBACK_SOURCES = 3;

    private DocAnswerRenderer() {}

    // STUDY: 답변 본문은 모델이 쓴 글이고 그 재료는 문서 원문이다. <!channel> 이나 <@U…> 같은 문자열이 섞여
    //        있으면 Slack 이 멘션으로 처리하므로 & < > 를 이스케이프한 뒤에 우리가 만든 링크만 붙인다.
    public static String render(RagClient.SearchResult result, DocAnswer answer) {
        StringBuilder sb = new StringBuilder();
        sb.append(":books: *").append(escape(result.source())).append(" 문서*\n");
        sb.append(escape(cut(answer.answer())));
        List<Integer> shown = answer.used().isEmpty() ? firstN(result.hits().size(), FALLBACK_SOURCES) : answer.used();
        // 근거로 쓴 조각이 없으면(못 찾았거나 모델이 적지 않았으면) 가장 가까운 문서를 대신 보여 준다.
        String title = answer.found() && !answer.used().isEmpty() ? "출처" : "가장 가까운 문서";
        appendSources(sb, title, result, shown);
        return sb.toString();
    }

    /** Claude 가 답을 만들지 못했을 때 — 찾은 문서만 알려 준다. */
    public static String sourcesOnly(RagClient.SearchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(":books: *").append(escape(result.source())).append(" 문서*\n");
        sb.append("답변을 만들지 못했어요. 관련 있어 보이는 문서만 알려드려요.");
        appendSources(sb, "관련 문서", result, firstN(result.hits().size(), MAX_SOURCES));
        return sb.toString();
    }

    private static void appendSources(StringBuilder sb, String title, RagClient.SearchResult result,
                                      List<Integer> indexes) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (int idx : indexes) {
            if (idx < 1 || idx > result.hits().size()) {
                continue;
            }
            RagClient.Hit hit = result.hits().get(idx - 1);
            if (!seen.add(hit.path() + "\n" + hit.heading())) {
                continue;
            }
            lines.add("[" + idx + "] " + link(result.repo(), result.commit(), hit));
            if (lines.size() == MAX_SOURCES) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n\n*").append(title).append("*\n").append(String.join("\n", lines));
        if (!result.commit().isBlank()) {
            sb.append("\n_문서 기준 커밋 ").append(escape(shortSha(result.commit()))).append("_");
        }
    }

    static String link(String repo, String commit, RagClient.Hit hit) {
        String label = hit.heading().isBlank() ? hit.path() : hit.path() + " › " + hit.heading();
        // 링크 표시 글자에 | 가 있으면 Slack 이 주소와 글자의 경계로 읽는다.
        label = escape(label).replace("|", "/");
        if (repo.isBlank() || commit.isBlank()) {
            return label;
        }
        String path = java.util.Arrays.stream(hit.path().split("/"))
                .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return "<https://github.com/" + repo + "/blob/" + commit + "/" + path + "|" + label + ">";
    }

    private static List<Integer> firstN(int size, int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= Math.min(size, n); i++) {
            out.add(i);
        }
        return out;
    }

    private static String cut(String s) {
        return s.length() > MAX_ANSWER_CHARS ? s.substring(0, MAX_ANSWER_CHARS) + "…" : s;
    }

    private static String shortSha(String sha) {
        return sha.substring(0, Math.min(8, sha.length()));
    }
}
