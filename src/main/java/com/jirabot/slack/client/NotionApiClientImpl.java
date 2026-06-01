package com.jirabot.slack.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.config.NotionProperties;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

// STUDY: SlackNotifierImpl 과 동일하게 WebClient 를 로컬 생성한다(이 클래스만 Notion API 사용).
//        인증은 Bearer 토큰 + Notion-Version 헤더. 모든 호출은 예외를 흡수하고 warn 만 — Notion 장애가
//        Jira/Slack 본 흐름(완료 처리)을 깨지 않도록 한다.
@Component
public class NotionApiClientImpl implements NotionApiClient {

    private static final Logger log = LoggerFactory.getLogger(NotionApiClientImpl.class);
    private static final String TITLE_PROP = "이슈";

    private final WebClient notionWebClient;
    private final ObjectMapper objectMapper;

    public NotionApiClientImpl(NotionProperties props, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.notionWebClient = WebClient.builder()
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader("Authorization", "Bearer " + (props.token() == null ? "" : props.token()))
                .defaultHeader("Notion-Version", props.version())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public Optional<String> findPageId(String databaseId, String issueKey) {
        try {
            // STUDY(버그 수정): 제목은 "KEY summary" 형태. Notion title 필터 contains/starts_with 는 부분일치라
            //        "ES2-1" 이 "ES2-1012" 까지 잡는다 → upsert 가 엉뚱한 row 를 덮어쓴다. 그래서 contains 로
            //        후보를 모은 뒤(page_size 100), 제목의 첫 토큰이 issueKey 와 정확히 일치하는 row 만 채택한다.
            Map<String, Object> body = Map.of(
                    "filter", Map.of("property", TITLE_PROP, "title", Map.of("contains", issueKey)),
                    "page_size", 100);
            String resp = notionWebClient.post()
                    .uri("/databases/{id}/query", databaseId)
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode results = objectMapper.readTree(resp).path("results");
            if (results.isArray()) {
                for (JsonNode row : results) {
                    JsonNode titleArr = row.path("properties").path(TITLE_PROP).path("title");
                    String title = titleArr.isArray() && !titleArr.isEmpty()
                            ? titleArr.get(0).path("plain_text").asText("") : "";
                    if (titleMatchesKey(title, issueKey)) {
                        return Optional.ofNullable(row.path("id").asText(null));
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Notion findPageId failed db={} issue={}: {}", databaseId, issueKey, e.toString());
            return Optional.empty();
        }
    }

    // STUDY: 제목 "KEY summary" 의 첫 토큰이 issueKey 와 정확히 같은지. contains 부분일치 충돌(ES2-1 ↔ ES2-1012) 방지.
    static boolean titleMatchesKey(String title, String issueKey) {
        if (title == null || title.isBlank() || issueKey == null) {
            return false;
        }
        String firstToken = title.strip().split(" ", 2)[0];
        return issueKey.equals(firstToken);
    }

    @Override
    public void createRow(String databaseId, Map<String, Object> properties) {
        try {
            Map<String, Object> body = Map.of(
                    "parent", Map.of("database_id", databaseId),
                    "properties", properties);
            notionWebClient.post().uri("/pages")
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();
        } catch (Exception e) {
            log.warn("Notion createRow failed db={}: {}", databaseId, e.toString());
        }
    }

    @Override
    public void updateRow(String pageId, Map<String, Object> properties) {
        try {
            notionWebClient.patch().uri("/pages/{id}", pageId)
                    .bodyValue(Map.of("properties", properties))
                    .retrieve().bodyToMono(String.class).block();
        } catch (Exception e) {
            log.warn("Notion updateRow failed page={}: {}", pageId, e.toString());
        }
    }
}
