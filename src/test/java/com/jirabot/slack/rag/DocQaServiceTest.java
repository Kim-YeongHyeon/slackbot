package com.jirabot.slack.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocQaServiceTest {

    private static final RagClient.SearchResult RESULT = new RagClient.SearchResult("envector", "org/repo", "abc123",
            "vector", List.of(new RagClient.Hit(12, "docs/runbooks/rollback.md", "Steps", "helm rollback", 0.7)));

    private RagClient ragClient;
    private DocAnswerer answerer;

    @BeforeEach
    void setUp() {
        ragClient = mock(RagClient.class);
        answerer = mock(DocAnswerer.class);
    }

    private DocQaService service(boolean enabled) {
        return new DocQaService(new RagProperties(enabled, null, 6, 20, 90, ""), ragClient, answerer);
    }

    @Test
    void answer_searchesThenAnswersAndRendersSources() {
        when(ragClient.search("envector", "롤백은 어떻게 해?", 6)).thenReturn(RESULT);
        when(answerer.answer("롤백은 어떻게 해?", RESULT.hits()))
                .thenReturn(Optional.of(new DocAnswer(true, "`helm rollback` 으로 해요 [1]", List.of(1))));

        String out = service(true).answerNow(" EnVector ", "  롤백은 어떻게 해?  ");

        assertThat(out).contains("`helm rollback` 으로 해요 [1]").contains("*출처*")
                .contains("https://github.com/org/repo/blob/abc123/docs/runbooks/rollback.md");
    }

    @Test
    void answer_asyncWrapperReturnsTheSameText() {
        when(ragClient.search(anyString(), anyString(), anyInt())).thenReturn(RESULT);
        when(answerer.answer(anyString(), any())).thenReturn(Optional.of(new DocAnswer(true, "답", List.of(1))));

        assertThat(service(true).answer("envector", "q").join()).contains("답");
    }

    @Test
    void answer_disabled_doesNotCallAnything() {
        assertThat(service(false).answerNow("envector", "q")).contains("꺼져 있어요");
        verify(ragClient, never()).search(any(), any(), anyInt());
    }

    @Test
    void answer_blankSourceOrQuestion_showsUsage() {
        assertThat(service(true).answerNow("envector", "  ")).isEqualTo(DocQaService.USAGE);
        assertThat(service(true).answerNow(null, "q")).isEqualTo(DocQaService.USAGE);
        verify(ragClient, never()).search(any(), any(), anyInt());
    }

    @Test
    void answer_unknownSource_listsAvailableSources() {
        when(ragClient.search(anyString(), anyString(), anyInt())).thenThrow(
                new RagException(RagException.Kind.UNKNOWN_SOURCE, "unknown_source", List.of("envector", "he-kms")));

        String out = service(true).answerNow("게시판에서", "500 에러");

        assertThat(out).contains("`게시판에서` 라는 문서 소스는 없어요").contains("`envector`, `he-kms`")
                .contains("`@지라 질문 envector 롤백은 어떻게 해?`");
        verify(answerer, never()).answer(any(), any());
    }

    @Test
    void answer_unknownSource_nameCannotInjectMentionsOrFormatting() {
        when(ragClient.search(anyString(), anyString(), anyInt())).thenThrow(
                new RagException(RagException.Kind.UNKNOWN_SOURCE, "unknown_source", List.of("envector")));

        String out = service(true).answerNow("<!channel>`x", "q");

        assertThat(out).doesNotContain("<!channel>").contains("`!channelx`");
    }

    @Test
    void answer_noSourcesAtAll() {
        when(ragClient.search(anyString(), anyString(), anyInt())).thenThrow(
                new RagException(RagException.Kind.UNKNOWN_SOURCE, "unknown_source", List.of()));

        assertThat(service(true).answerNow("envector", "q")).contains("아직 준비된 문서 소스가 없어요");
    }

    @Test
    void answer_notReadyBadRequestAndUnavailable_haveTheirOwnMessages() {
        when(ragClient.search(anyString(), anyString(), anyInt()))
                .thenThrow(new RagException(RagException.Kind.NOT_READY, "not_built"))
                .thenThrow(new RagException(RagException.Kind.BAD_REQUEST, "query_too_long"))
                .thenThrow(new RagException(RagException.Kind.UNAVAILABLE, "connection refused"));

        assertThat(service(true).answerNow("envector", "q")).contains("아직 준비되지 않았어요");
        assertThat(service(true).answerNow("envector", "q")).contains("너무 길어요");
        assertThat(service(true).answerNow("envector", "q")).contains("연결하지 못했어요")
                .doesNotContain("connection refused");
    }

    @Test
    void answer_noHits_saysNothingFoundWithoutCallingClaude() {
        when(ragClient.search(anyString(), anyString(), anyInt()))
                .thenReturn(new RagClient.SearchResult("envector", "org/repo", "abc", "vector", List.of()));

        assertThat(service(true).answerNow("envector", "q")).contains("관련 내용을 찾지 못했어요");
        verify(answerer, never()).answer(any(), any());
    }

    @Test
    void answer_claudeFailure_fallsBackToSourceList() {
        when(ragClient.search(anyString(), anyString(), anyInt())).thenReturn(RESULT);
        when(answerer.answer(anyString(), any())).thenReturn(Optional.empty());

        String out = service(true).answerNow("envector", "q");

        assertThat(out).contains("답변을 만들지 못했어요").contains("*관련 문서*").contains("docs/runbooks/rollback.md");
    }
}
