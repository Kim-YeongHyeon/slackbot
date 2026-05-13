package com.jirabot.slack.service;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.dto.IssueSearchEntry;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: @Async("slackTaskExecutor") 패턴으로 비동기 실행. 컨트롤러는 CompletableFuture.thenAccept()로 결과만 받는다.
//        ScrumReportServiceImpl과 동일한 패턴.
@Service
public class IssueSearchServiceImpl implements IssueSearchService {

    private static final Logger log = LoggerFactory.getLogger(IssueSearchServiceImpl.class);

    // STUDY: DB에서 가져올 최대 결과 수. 포맷팅에서는 10개만 보여주지만, DB 쿼리 부하를 제한하기 위해 50개로 제한.
    static final int MAX_SEARCH_RESULTS = 50;
    private static final int SEARCH_MAX_DISPLAY = 10;

    private final IssueRepository issueRepository;
    private final ClaudeApiClient claudeApiClient;
    private final JiraSyncService jiraSyncService;
    private final String jiraBaseUrl;

    public IssueSearchServiceImpl(IssueRepository issueRepository,
                                  ClaudeApiClient claudeApiClient,
                                  JiraSyncService jiraSyncService,
                                  JiraProperties jiraProps) {
        this.issueRepository = issueRepository;
        this.claudeApiClient = claudeApiClient;
        this.jiraSyncService = jiraSyncService;
        String base = jiraProps.baseUrl() == null ? "" : jiraProps.baseUrl();
        this.jiraBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    // STUDY: 검색 freshness 를 위해 sync 를 먼저 돌린다. 매 호출 2~3s 지연이 추가되지만 사용자가
    //        Jira 에서 방금 만든 이슈/변경된 상태도 결과에 반영됨. sync 실패는 검색을 막지 않는다
    //        (있는 로컬 데이터로라도 응답).
    private void prefetchFromJira() {
        try {
            jiraSyncService.syncActiveSprint();
            jiraSyncService.syncBacklog();
        } catch (Exception e) {
            log.warn("Pre-search sync failed, proceeding with current DB state: {}", e.toString());
        }
    }

    // STUDY: @Async 메서드는 Spring 프록시를 통해 호출되어야 비동기가 적용된다.
    //        같은 클래스 내부에서 호출하면 프록시를 거치지 않아 동기 실행됨.
    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> searchByKeyword(String keyword) {
        log.info("Keyword search requested: keyword='{}'", keyword);
        prefetchFromJira();
        List<IssueEntity> results = issueRepository.searchByKeyword(escapeWildcards(keyword), PageRequest.of(0, MAX_SEARCH_RESULTS));
        return CompletableFuture.completedFuture(formatSearchResults(keyword, results));
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<String> searchSemantic(String userQuery, String fallbackKeyword) {
        log.info("Semantic search requested: query='{}' fallback='{}'", userQuery, fallbackKeyword);
        prefetchFromJira();
        try {
            List<IssueEntity> allIssues = issueRepository.findAll();
            if (allIssues.isEmpty()) {
                return CompletableFuture.completedFuture(
                        ":mag: 검색할 이슈가 없습니다. `@지라 sync`로 먼저 동기화해주세요.");
            }

            List<IssueSearchEntry> entries = allIssues.stream()
                    .map(issue -> new IssueSearchEntry(
                            issue.getIssueKey(),
                            issue.getSummary(),
                            issue.getDescription(),
                            issue.getStatusCategory(),
                            issue.getAssignee()))
                    .toList();

            List<String> matchedKeys = claudeApiClient.searchIssues(userQuery, entries);

            if (matchedKeys == null || matchedKeys.isEmpty()) {
                log.info("Sonnet returned empty results, falling back to keyword search: '{}'", fallbackKeyword);
                // STUDY: Sonnet 빈 결과 시 키워드 fallback. 동일 스레드에서 직접 호출 (self-invocation이므로 @Async 무시됨, 이미 비동기 스레드 안이라 OK).
                List<IssueEntity> keywordResults = issueRepository.searchByKeyword(
                        escapeWildcards(fallbackKeyword), PageRequest.of(0, MAX_SEARCH_RESULTS));
                return CompletableFuture.completedFuture(formatSearchResults(fallbackKeyword, keywordResults));
            }

            // STUDY: Sonnet이 반환한 키 순서(관련도순)를 유지하면서 DB 엔티티를 매칭한다.
            Map<String, IssueEntity> issueMap = allIssues.stream()
                    .collect(Collectors.toMap(IssueEntity::getIssueKey, issue -> issue, (a, b) -> a));
            List<IssueEntity> matched = matchedKeys.stream()
                    .filter(issueMap::containsKey)
                    .map(issueMap::get)
                    .toList();

            if (matched.isEmpty()) {
                return CompletableFuture.completedFuture(
                        String.format(":mag: \"%s\" 검색 결과가 없습니다.", userQuery));
            }

            return CompletableFuture.completedFuture(formatSearchResults(userQuery, matched));

        } catch (Exception e) {
            log.warn("Semantic search failed, falling back to keyword search: {}", e.toString());
            List<IssueEntity> keywordResults = issueRepository.searchByKeyword(
                    escapeWildcards(fallbackKeyword), PageRequest.of(0, MAX_SEARCH_RESULTS));
            return CompletableFuture.completedFuture(formatSearchResults(fallbackKeyword, keywordResults));
        }
    }

    // STUDY: LIKE 와일드카드 이스케이프. 사용자 입력에 %, _, \ 가 포함되면 JPQL LIKE 쿼리에서 예기치 않은 매칭을 일으킬 수 있다.
    //        ESCAPE '\' 절과 함께 사용하여 리터럴 문자로 취급한다.
    String escapeWildcards(String keyword) {
        return keyword.replace("\\", "\\\\")
                      .replace("%", "\\%")
                      .replace("_", "\\_");
    }

    // STUDY: 패키지-프라이빗으로 선언하여 테스트에서 직접 호출 가능.
    String formatSearchResults(String keyword, List<IssueEntity> results) {
        if (results.isEmpty()) {
            return String.format(":mag: \"%s\" 검색 결과가 없습니다.", keyword);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(":mag: \"%s\" 검색 결과 (%d건)\n", keyword, results.size()));

        int displayCount = Math.min(results.size(), SEARCH_MAX_DISPLAY);
        for (int i = 0; i < displayCount; i++) {
            IssueEntity issue = results.get(i);
            String url = issueLink(issue.getIssueKey());
            String assignee = issue.getAssignee() != null ? issue.getAssignee() : "미배정";
            String sp = issue.getStoryPoint() != null
                    ? String.valueOf(issue.getStoryPoint().intValue()) : "-";
            sb.append(String.format("• <%s|%s> %s (%s, SP %s, 담당: %s)\n",
                    url, issue.getIssueKey(), issue.getSummary(),
                    issue.getStatusCategory(), sp, assignee));
        }

        if (results.size() > SEARCH_MAX_DISPLAY) {
            sb.append(String.format("외 %d건이 더 있습니다.", results.size() - SEARCH_MAX_DISPLAY));
        }

        return sb.toString().stripTrailing();
    }

    private String issueLink(String key) {
        if (jiraBaseUrl.isEmpty()) return key;
        return jiraBaseUrl + "/browse/" + key;
    }
}
