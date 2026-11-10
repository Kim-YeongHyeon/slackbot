package com.jirabot.slack.rag;

import com.jirabot.slack.config.AsyncConfig;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: `@지라 질문 <소스> <질문>` 의 본체. 검색(rag 사이드카) → 답변(Claude) → Slack 답글 문자열.
//        어느 단계가 실패해도 예외를 밖으로 내지 않고 안내 문구를 돌려준다 — 봇의 다른 기능에 영향을 주지 않는다.
@Service
public class DocQaService {

    private static final Logger log = LoggerFactory.getLogger(DocQaService.class);
    public static final String USAGE = ":books: 문서에 물어볼 내용을 입력해주세요. 예: `@지라 질문 envector 롤백은 어떻게 해?`";

    private final RagProperties props;
    private final RagClient ragClient;
    private final DocAnswerer answerer;

    public DocQaService(RagProperties props, RagClient ragClient, DocAnswerer answerer) {
        this.props = props;
        this.ragClient = ragClient;
        this.answerer = answerer;
    }

    // STUDY: @Async("slackTaskExecutor") — Slack 이벤트는 3초 안에 200 을 돌려줘야 하므로 오래 걸리는 일은
    //        별도 스레드 풀에서 하고, 컨트롤러는 CompletableFuture.thenAccept() 로 결과만 받는다.
    @Async(AsyncConfig.SLACK_EXECUTOR)
    public CompletableFuture<String> answer(String source, String question) {
        return CompletableFuture.completedFuture(answerNow(source, question));
    }

    String answerNow(String source, String question) {
        if (!props.enabled()) {
            return ":information_source: 문서 질문 기능이 꺼져 있어요.";
        }
        if (source == null || source.isBlank() || question == null || question.isBlank()) {
            return USAGE;
        }
        RagClient.SearchResult result;
        try {
            result = ragClient.search(source.strip().toLowerCase(), question.strip(), props.topK());
        } catch (RagException e) {
            log.warn("Doc search failed source={} kind={} detail={}", source, e.kind(), e.getMessage());
            return failureMessage(source, e);
        }
        if (result.hits().isEmpty()) {
            return ":mag: `" + escapeCode(result.source()) + "` 문서에서 관련 내용을 찾지 못했어요.";
        }
        Optional<DocAnswer> answer = answerer.answer(question.strip(), result.hits());
        return answer.map(a -> DocAnswerRenderer.render(result, a))
                .orElseGet(() -> DocAnswerRenderer.sourcesOnly(result));
    }

    static String failureMessage(String source, RagException e) {
        return switch (e.kind()) {
            case UNKNOWN_SOURCE -> e.sources().isEmpty()
                    ? ":mag: 아직 준비된 문서 소스가 없어요."
                    : ":mag: `" + escapeCode(source) + "` 라는 문서 소스는 없어요. 쓸 수 있는 소스: "
                            + String.join(", ", e.sources().stream().map(s -> "`" + escapeCode(s) + "`").toList())
                            + "\n예: `@지라 질문 " + escapeCode(e.sources().get(0)) + " 롤백은 어떻게 해?`";
            case NOT_READY -> ":hourglass_flowing_sand: `" + escapeCode(source) + "` 문서는 아직 준비되지 않았어요.";
            case BAD_REQUEST -> ":x: 질문이 비어 있거나 너무 길어요. 2,000자 안으로 줄여 주세요.";
            case UNAVAILABLE -> ":x: 문서 검색 서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
        };
    }

    // 사용자가 넣은 소스 이름을 `코드`로 되돌려 보여 줄 때 — 백틱과 Slack 제어 문자를 빼서 서식·멘션이 깨지지 않게 한다.
    private static String escapeCode(String s) {
        String cleaned = s == null ? "" : s.replaceAll("[`<>&\\s]", "");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
