package com.jirabot.slack.rag;

import java.util.List;

/** rag 사이드카(문서 검색 서버) 호출. 실패는 {@link RagException} 으로 알린다. */
public interface RagClient {

    SearchResult search(String source, String query, int topK);

    /**
     * @param repo   문서가 있는 GitHub 저장소(owner/repo). 출처 링크를 만드는 데 쓴다.
     * @param commit 조각을 만든 시점의 커밋 — 링크가 그때의 문서를 가리키게 한다.
     */
    record SearchResult(String source, String repo, String commit, String mode, List<Hit> hits) {
        public SearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    /**
     * @param no      조각 번호 (소스 안에서 바뀌지 않는 번호)
     * @param heading 문서 안의 제목 경로 ("A > B")
     */
    record Hit(int no, String path, String heading, String text, double score) {}
}
