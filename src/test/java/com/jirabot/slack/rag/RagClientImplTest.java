package com.jirabot.slack.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RagClientImplTest {

    private MockWebServer server;
    private RagClientImpl client;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new RagClientImpl(RagClientImpl.webClient(server.url("/").toString(), 2), om, Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body);
    }

    @Test
    void search_sendsRequestAndParsesHits() throws Exception {
        server.enqueue(json(200, """
                {"source":"envector","repo":"org/repo","commit":"abc123","mode":"vector","hits":[
                  {"no":12,"path":"docs/runbooks/rollback.md","heading":"Rollback > Steps","text":"본문 1","score":0.71},
                  {"no":40,"path":"docs/ha/x.md","heading":"","text":"본문 2","score":0.5}]}"""));

        RagClient.SearchResult result = client.search("envector", "롤백은 어떻게 해?", 6);

        assertThat(result.source()).isEqualTo("envector");
        assertThat(result.repo()).isEqualTo("org/repo");
        assertThat(result.commit()).isEqualTo("abc123");
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0))
                .isEqualTo(new RagClient.Hit(12, "docs/runbooks/rollback.md", "Rollback > Steps", "본문 1", 0.71));
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/search");
        JsonNode sent = om.readTree(req.getBody().readUtf8());
        assertThat(sent.path("source").asText()).isEqualTo("envector");
        assertThat(sent.path("query").asText()).isEqualTo("롤백은 어떻게 해?");
        assertThat(sent.path("top_k").asInt()).isEqualTo(6);
    }

    @Test
    void search_noHits_returnsEmptyList() {
        server.enqueue(json(200, "{\"source\":\"envector\",\"repo\":\"\",\"commit\":\"\",\"mode\":\"vector\",\"hits\":[]}"));

        assertThat(client.search("envector", "q", 6).hits()).isEmpty();
    }

    @Test
    void search_unknownSource_carriesAvailableSources() {
        server.enqueue(json(404, "{\"error\":\"unknown_source\",\"sources\":[\"envector\",\"he-kms\"]}"));

        assertThatThrownBy(() -> client.search("nope", "q", 6))
                .isInstanceOfSatisfying(RagException.class, e -> {
                    assertThat(e.kind()).isEqualTo(RagException.Kind.UNKNOWN_SOURCE);
                    assertThat(e.sources()).containsExactly("envector", "he-kms");
                });
    }

    @Test
    void search_sourceNotBuilt_isNotReady() {
        server.enqueue(json(503, "{\"error\":\"source_not_ready\",\"source\":\"envector\",\"detail\":\"not_built\"}"));

        assertThatThrownBy(() -> client.search("envector", "q", 6))
                .isInstanceOfSatisfying(RagException.class, e -> {
                    assertThat(e.kind()).isEqualTo(RagException.Kind.NOT_READY);
                    assertThat(e.getMessage()).isEqualTo("not_built");
                });
    }

    @Test
    void search_badRequest_isBadRequest() {
        server.enqueue(json(400, "{\"error\":\"query_too_long\",\"max\":2000}"));

        assertThatThrownBy(() -> client.search("envector", "q", 6))
                .isInstanceOfSatisfying(RagException.class,
                        e -> assertThat(e.kind()).isEqualTo(RagException.Kind.BAD_REQUEST));
    }

    @Test
    void search_serverError_isUnavailable() {
        server.enqueue(json(500, "{\"error\":\"internal\"}"));

        assertThatThrownBy(() -> client.search("envector", "q", 6))
                .isInstanceOfSatisfying(RagException.class,
                        e -> assertThat(e.kind()).isEqualTo(RagException.Kind.UNAVAILABLE));
    }

    @Test
    void search_nonJsonBody_isUnavailable() {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"));

        assertThatThrownBy(() -> client.search("envector", "q", 6))
                .isInstanceOfSatisfying(RagException.class,
                        e -> assertThat(e.kind()).isEqualTo(RagException.Kind.UNAVAILABLE));
    }

    @Test
    void search_noResponse_isUnavailable() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> client.search("envector", "q", 6))
                .isInstanceOfSatisfying(RagException.class,
                        e -> assertThat(e.kind()).isEqualTo(RagException.Kind.UNAVAILABLE));
    }

    @Test
    void search_sidecarDown_isUnavailable() throws IOException {
        String base = server.url("/").toString();
        server.shutdown();
        RagClientImpl down = new RagClientImpl(RagClientImpl.webClient(base, 2), om, Duration.ofSeconds(2));

        assertThatThrownBy(() -> down.search("envector", "q", 6))
                .isInstanceOfSatisfying(RagException.class,
                        e -> assertThat(e.kind()).isEqualTo(RagException.Kind.UNAVAILABLE));
    }
}
