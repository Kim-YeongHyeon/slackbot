package com.jirabot.slack.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
public class RagClientImpl implements RagClient {

    private final WebClient web;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    @Autowired
    public RagClientImpl(RagProperties props, ObjectMapper objectMapper) {
        this(webClient(props.baseUrl(), props.searchTimeoutSeconds()), objectMapper,
                Duration.ofSeconds(props.searchTimeoutSeconds()));
    }

    RagClientImpl(WebClient web, ObjectMapper objectMapper, Duration timeout) {
        this.web = web;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    static WebClient webClient(String baseUrl, int timeoutSeconds) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                // 조각 원문 20개까지 실려 온다 — 기본 256KB 버퍼를 넘지 않게 넉넉히.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                        .responseTimeout(Duration.ofSeconds(timeoutSeconds))))
                .build();
    }

    private record Raw(int status, String body) {}

    @Override
    public SearchResult search(String source, String query, int topK) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("source", source);
        request.put("query", query);
        request.put("top_k", topK);
        Raw raw;
        try {
            // STUDY: exchangeToMono — 2xx 가 아니어도 예외로 바꾸지 않고 상태 코드와 본문을 함께 받는다.
            //        사이드카는 오류도 JSON({"error": "..."})으로 주므로 본문을 읽어야 안내를 만들 수 있다.
            raw = web.post().uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> new Raw(resp.statusCode().value(), body)))
                    .block(timeout.plusSeconds(2));
        } catch (RuntimeException e) {
            throw new RagException(RagException.Kind.UNAVAILABLE, "rag sidecar unreachable: " + e);
        }
        if (raw == null) {
            throw new RagException(RagException.Kind.UNAVAILABLE, "rag sidecar gave no response");
        }
        JsonNode json;
        try {
            json = objectMapper.readTree(raw.body());
        } catch (Exception e) {
            throw new RagException(RagException.Kind.UNAVAILABLE, "rag sidecar sent non-JSON, status=" + raw.status());
        }
        if (raw.status() == 200) {
            return toResult(json);
        }
        String error = json.path("error").asText("");
        switch (raw.status()) {
            case 404 -> {
                List<String> sources = new ArrayList<>();
                json.path("sources").forEach(s -> sources.add(s.asText()));
                throw new RagException(RagException.Kind.UNKNOWN_SOURCE, error, sources);
            }
            case 503 -> throw new RagException(RagException.Kind.NOT_READY, json.path("detail").asText(error));
            case 400, 413 -> throw new RagException(RagException.Kind.BAD_REQUEST, error);
            default -> throw new RagException(RagException.Kind.UNAVAILABLE,
                    "rag sidecar status=" + raw.status() + " error=" + error);
        }
    }

    private static SearchResult toResult(JsonNode json) {
        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : json.path("hits")) {
            hits.add(new Hit(h.path("no").asInt(), h.path("path").asText(""), h.path("heading").asText(""),
                    h.path("text").asText(""), h.path("score").asDouble()));
        }
        return new SearchResult(json.path("source").asText(""), json.path("repo").asText(""),
                json.path("commit").asText(""), json.path("mode").asText(""), hits);
    }
}
