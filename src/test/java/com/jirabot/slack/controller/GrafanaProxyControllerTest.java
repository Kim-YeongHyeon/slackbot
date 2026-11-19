package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.config.DashboardEmbedProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

// 가짜 Grafana(JDK HttpServer)를 띄워 프록시가 실제 HTTP 로 무엇을 넘기고 돌려주는지 본다.
class GrafanaProxyControllerTest {

    private HttpServer upstream;
    private final Map<String, Object> seen = new ConcurrentHashMap<>();
    private volatile int replyStatus = 200;
    private volatile Map<String, String> replyHeaders = Map.of();
    private volatile String replyBody = "ok";

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            seen.put("method", exchange.getRequestMethod());
            seen.put("path", exchange.getRequestURI().getRawPath());
            seen.put("query", String.valueOf(exchange.getRequestURI().getRawQuery()));
            seen.put("headers", exchange.getRequestHeaders());
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            replyHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] body = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(replyStatus, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private GrafanaProxyController controller() {
        return new GrafanaProxyController(new DashboardEmbedProperties(null,
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/"));
    }

    private static MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setQueryString(query);
        return req;
    }

    @SuppressWarnings("unchecked")
    private com.sun.net.httpserver.Headers seenHeaders() {
        return (com.sun.net.httpserver.Headers) seen.get("headers");
    }

    @Test
    void forwardsPathAndQuery_withoutSolCredentialsOrCookies() throws Exception {
        MockHttpServletRequest req = request("GET", "/grafana/api/dashboards/uid/abc", "orgId=1&from=now-6h");
        req.addHeader("Authorization", "Basic c29sOnB3");
        req.addHeader("Cookie", "x=1");
        req.addHeader("X-Grafana-Org-Id", "1");
        req.addHeader("Accept", "application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller().proxy(req, resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get("path")).isEqualTo("/grafana/api/dashboards/uid/abc");
        assertThat(seen.get("query")).isEqualTo("orgId=1&from=now-6h");
        assertThat(seenHeaders().containsKey("Authorization")).isFalse();
        assertThat(seenHeaders().containsKey("Cookie")).isFalse();
        assertThat(seenHeaders().getFirst("X-Grafana-Org-Id")).isEqualTo("1");
        assertThat(seenHeaders().getFirst("Accept")).isEqualTo("application/json");
    }

    @Test
    void relaysStatusHeadersAndBody_butDropsSetCookie() throws Exception {
        replyStatus = 201;
        replyHeaders = Map.of("X-Test", "yes", "Set-Cookie", "grafana_session=abc", "Content-Type", "text/plain");
        replyBody = "안녕";
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller().proxy(request("GET", "/grafana/public/x.js", null), resp);

        assertThat(resp.getStatus()).isEqualTo(201);
        assertThat(resp.getHeader("X-Test")).isEqualTo("yes");
        assertThat(resp.getHeader("Content-Type")).isEqualTo("text/plain");
        assertThat(resp.getHeaders("Set-Cookie")).isEmpty();
        assertThat(new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8)).isEqualTo("안녕");
    }

    @Test
    void forwardsPostBody() throws Exception {
        MockHttpServletRequest req = request("POST", "/grafana/api/ds/query", null);
        req.setContentType("application/json");
        req.addHeader("Content-Type", "application/json");
        req.setContent("{\"queries\":[]}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller().proxy(req, resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get("method")).isEqualTo("POST");
        assertThat(seen.get("body")).isEqualTo("{\"queries\":[]}");
        assertThat(seenHeaders().getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void redirectIsReturnedToTheBrowser_notFollowed() throws Exception {
        replyStatus = 302;
        replyHeaders = Map.of("Location", "/grafana/d/abc");
        replyBody = "";
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller().proxy(request("GET", "/grafana", null), resp);

        assertThat(resp.getStatus()).isEqualTo(302);
        assertThat(resp.getHeader("Location")).isEqualTo("/grafana/d/abc");
        assertThat(seen.get("path")).isEqualTo("/grafana");
    }

    @Test
    void bracketsInQuery_reencoded() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller().proxy(request("GET", "/grafana/api/x", "match[]=up{job=\"a\"}"), resp);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(seen.get("query")).isEqualTo("match%5B%5D=up%7Bjob=%22a%22%7D");
    }

    @Test
    void notConfigured_404_withoutContactingAnything() throws Exception {
        var controller = new GrafanaProxyController(new DashboardEmbedProperties(null, ""));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.proxy(request("GET", "/grafana/", null), resp);

        assertThat(resp.getStatus()).isEqualTo(404);
        assertThat(seen).isEmpty();
    }

    @Test
    void grafanaDown_502() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        var controller = new GrafanaProxyController(
                new DashboardEmbedProperties(null, "http://127.0.0.1:" + closedPort));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.proxy(request("GET", "/grafana/", null), resp);

        assertThat(resp.getStatus()).isEqualTo(502);
        assertThat(new String(resp.getContentAsByteArray(), StandardCharsets.UTF_8)).contains("연결할 수 없어요");
    }

    @Test
    void encodeIllegal_keepsEscapesAndLegalChars_encodesTheRest() {
        assertThat(GrafanaProxyController.encodeIllegal("/grafana/d/a-b_c.d~e?x=%20&y=1:2@3"))
                .isEqualTo("/grafana/d/a-b_c.d~e?x=%20&y=1:2@3");
        assertThat(GrafanaProxyController.encodeIllegal("a b|c^`\\<>"))
                .isEqualTo("a%20b%7Cc%5E%60%5C%3C%3E");
        assertThat(GrafanaProxyController.encodeIllegal("서")).isEqualTo("%EC%84%9C");
    }

    @Test
    void requestDropList_coversCredentialsAndHopByHop() {
        assertThat(GrafanaProxyController.DROP_REQUEST)
                .contains("authorization", "cookie", "host", "connection", "transfer-encoding", "content-length");
        assertThat(GrafanaProxyController.DROP_RESPONSE).contains("set-cookie", "transfer-encoding")
                .doesNotContain("content-type", "location");
        assertThat(List.copyOf(GrafanaProxyController.HOP_BY_HOP)).allMatch(h -> h.equals(h.toLowerCase()));
    }
}
