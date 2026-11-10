package com.jirabot.slack.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocAnswerRendererTest {

    private static RagClient.Hit hit(int no, String path, String heading) {
        return new RagClient.Hit(no, path, heading, "text", 0.5);
    }

    private static final RagClient.SearchResult RESULT = new RagClient.SearchResult("envector", "org/repo",
            "abc1234567890", "vector", List.of(
            hit(1, "docs/runbooks/rollback.md", "Rollback > Steps"),
            hit(2, "docs/ha/x.md", ""),
            hit(3, "docs/runbooks/rollback.md", "Rollback > Steps"),
            hit(4, "docs/specs/a b.md", "A | B")));

    @Test
    void render_showsAnswerAndOnlyCitedSourcesAsLinks() {
        String out = DocAnswerRenderer.render(RESULT, new DocAnswer(true, "`helm rollback` 으로 되돌려요 [1]", List.of(1)));

        assertThat(out).startsWith(":books: *envector 문서*\n`helm rollback` 으로 되돌려요 [1]");
        assertThat(out).contains("*출처*\n[1] <https://github.com/org/repo/blob/abc1234567890/docs/runbooks/rollback.md"
                + "|docs/runbooks/rollback.md › Rollback &gt; Steps>");
        assertThat(out).doesNotContain("docs/ha/x.md");
        assertThat(out).endsWith("_문서 기준 커밋 abc12345_");
    }

    @Test
    void render_escapesMentionsAndLinksInTheAnswer() {
        String out = DocAnswerRenderer.render(RESULT,
                new DocAnswer(true, "<!channel> 보세요 <@U123> <https://evil|여기> a & b", List.of(2)));

        assertThat(out).contains("&lt;!channel&gt; 보세요 &lt;@U123&gt; &lt;https://evil|여기&gt; a &amp; b");
        assertThat(out).doesNotContain("<!channel>").doesNotContain("<@U123>");
    }

    @Test
    void render_duplicateSectionIsListedOnce() {
        String out = DocAnswerRenderer.render(RESULT, new DocAnswer(true, "답", List.of(1, 3, 2)));

        assertThat(out).contains("[1] <").contains("[2] <").doesNotContain("[3] <");
    }

    @Test
    void render_encodesPathAndKeepsPipeOutOfTheLabel() {
        String out = DocAnswerRenderer.render(RESULT, new DocAnswer(true, "답", List.of(4)));

        assertThat(out).contains("<https://github.com/org/repo/blob/abc1234567890/docs/specs/a%20b.md"
                + "|docs/specs/a b.md › A / B>");
    }

    @Test
    void render_notFound_listsClosestDocuments() {
        String out = DocAnswerRenderer.render(RESULT, new DocAnswer(false, "문서에 없어요.", List.of()));

        assertThat(out).contains("문서에 없어요.").contains("*가장 가까운 문서*").doesNotContain("*출처*");
        assertThat(out).contains("[1] <").contains("[2] <");
    }

    @Test
    void render_foundButNoCitations_fallsBackToClosestDocuments() {
        String out = DocAnswerRenderer.render(RESULT, new DocAnswer(true, "답", List.of()));

        assertThat(out).contains("*가장 가까운 문서*");
    }

    @Test
    void render_cutsVeryLongAnswers() {
        String out = DocAnswerRenderer.render(RESULT, new DocAnswer(true, "가".repeat(5000), List.of(1)));

        assertThat(out).contains("가".repeat(DocAnswerRenderer.MAX_ANSWER_CHARS) + "…");
        assertThat(out).doesNotContain("가".repeat(DocAnswerRenderer.MAX_ANSWER_CHARS + 1));
    }

    @Test
    void render_withoutRepoOrCommit_showsPlainPathsAndNoCommitLine() {
        RagClient.SearchResult local = new RagClient.SearchResult("demo", "", "", "vector",
                List.of(hit(1, "docs/a.md", "H")));

        String out = DocAnswerRenderer.render(local, new DocAnswer(true, "답", List.of(1)));

        assertThat(out).contains("[1] docs/a.md › H").doesNotContain("https://").doesNotContain("커밋");
    }

    @Test
    void sourcesOnly_listsUpToFiveDocuments() {
        String out = DocAnswerRenderer.sourcesOnly(RESULT);

        assertThat(out).contains("답변을 만들지 못했어요").contains("*관련 문서*");
        assertThat(out).contains("[1] <").contains("[2] <").contains("[4] <");
    }
}
