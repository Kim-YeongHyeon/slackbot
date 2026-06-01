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
            // STUDY: 제목(이슈) 에 issueKey 가 포함된 row 를 조회. upsert 의 "존재 확인" 단계.
            Map<String, Object> body = Map.of(
                    "filter", Map.of("property", TITLE_PROP, "title", Map.of("contains", issueKey)),
                    "page_size", 1);
            String resp = notionWebClient.post()
                    .uri("/databases/{id}/query", databaseId)
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode results = objectMapper.readTree(resp).path("results");
            if (results.isArray() && !results.isEmpty()) {
                return Optional.ofNullable(results.get(0).path("id").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Notion findPageId failed db={} issue={}: {}", databaseId, issueKey, e.toString());
            return Optional.empty();
        }
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
