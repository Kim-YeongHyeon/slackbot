package com.jirabot.slack.service;

import java.util.concurrent.CompletableFuture;

public interface IssueSearchService {

    // STUDY: 키워드 DB 검색 (즉시). summary/description에서 LIKE 매칭.
    CompletableFuture<String> searchByKeyword(String keyword);

    // STUDY: Sonnet 의미 검색 (자연어, ~30초). Sonnet이 관련 이슈를 선별하고, 실패 시 키워드 fallback.
    CompletableFuture<String> searchSemantic(String userQuery, String fallbackKeyword);
}
