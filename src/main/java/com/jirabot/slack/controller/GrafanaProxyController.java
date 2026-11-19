package com.jirabot.slack.controller;

import com.jirabot.slack.config.DashboardEmbedProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /grafana/** → 같은 서버의 Grafana (v0.0.104). 임베드 탭의 iframe 이 같은 출처로 Grafana 를 띄우게 한다.
// - 인증은 SecurityConfig 가 먼저 한다(대시보드 로그인 필수). Grafana 는 127.0.0.1 에서 익명 Viewer 로만 열어 둔다.
// - sol 로그인 자격 증명(Authorization)과 쿠키는 Grafana 로 넘기지 않는다. Grafana 가 주는 Set-Cookie 도 버린다
//   — 쿠키가 없으니 Grafana 의 CSRF(로그인 쿠키 기준) 검사도 걸리지 않는다.
// - 경로는 그대로 넘긴다: Grafana 는 root_url .../grafana/ + serve_from_sub_path 로 /grafana 아래에서 동작한다.
// STUDY: 리버스 프록시는 hop-by-hop 헤더(Connection, Transfer-Encoding 등 한 구간에서만 의미 있는 헤더)를 다음 구간으로
//        넘기면 안 된다(RFC 9110 7.6.1). 본문은 메모리에 모으지 않고 InputStream 으로 바로 흘려보낸다(JS 번들이 수 MB).
@RestController
public class GrafanaProxyController {

    private static final Logger log = LoggerFactory.getLogger(GrafanaProxyController.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");
    // STUDY: java.net.http.HttpClient 는 host·content-length·expect·connection·upgrade 를 직접 정하고, 넣으면 예외를 던진다.
    static final Set<String> DROP_REQUEST = union(HOP_BY_HOP,
            Set.of("host", "content-length", "expect", "http2-settings", "authorization", "cookie"));
    static final Set<String> DROP_RESPONSE = union(HOP_BY_HOP, Set.of("set-cookie"));

    private final DashboardEmbedProperties props;
    private final HttpClient client;

    public GrafanaProxyController(DashboardEmbedProperties props) {
        this.props = props;
        // STUDY: 리다이렉트는 따라가지 않고 브라우저에 그대로 돌려준다(Location 이 /grafana/... 라 같은 출처로 다시 들어온다).
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @RequestMapping({"/grafana", "/grafana/**"})
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!props.grafanaEnabled()) {
            error(resp, HttpServletResponse.SC_NOT_FOUND, "Grafana 가 설정되지 않았어요");
            return;
        }
        URI target;
        try {
            String query = req.getQueryString();
            target = URI.create(props.grafanaUrl() + encodeIllegal(req.getRequestURI())
                    + (query == null ? "" : "?" + encodeIllegal(query)));
        } catch (IllegalArgumentException e) {
            error(resp, HttpServletResponse.SC_BAD_REQUEST, "잘못된 주소예요");
            return;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .method(req.getMethod(), body(req));
        for (String name : Collections.list(req.getHeaderNames())) {
            if (DROP_REQUEST.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (String value : Collections.list(req.getHeaders(name))) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // HttpClient 가 받지 않는 헤더 이름·값은 버린다.
                }
            }
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = client.send(builder.build(), BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            error(resp, HttpServletResponse.SC_GATEWAY_TIMEOUT, "Grafana 응답이 늦어요");
            return;
        } catch (IOException e) {
            log.warn("Grafana proxy failed: {} {} — {}", req.getMethod(), req.getRequestURI(), e.toString());
            error(resp, HttpServletResponse.SC_BAD_GATEWAY, "Grafana 에 연결할 수 없어요");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(resp, HttpServletResponse.SC_BAD_GATEWAY, "Grafana 요청이 중단됐어요");
            return;
        }

        resp.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(":") || DROP_RESPONSE.contains(lower)) return;
            values.forEach(v -> resp.addHeader(name, v));
        });
        try (InputStream in = upstream.body()) {
            in.transferTo(resp.getOutputStream());
        }
    }

    // 본문이 없는 요청(GET 등)은 noBody. 길이를 알면 그대로 넘겨 Content-Length 가 유지되게 한다.
    private static BodyPublisher body(HttpServletRequest req) {
        long length = req.getContentLengthLong();
        boolean chunked = "chunked".equalsIgnoreCase(req.getHeader("Transfer-Encoding"));
        if (length <= 0 && !chunked) return BodyPublishers.noBody();
        BodyPublisher stream = BodyPublishers.ofInputStream(() -> {
            try {
                return req.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return length > 0 ? BodyPublishers.fromPublisher(stream, length) : stream;
    }

    // STUDY: Tomcat 은 [ ] { } | 같은 문자를 relaxed-query-chars 로 받아 주지만 java.net.URI 는 이를 거부한다.
    //        이미 인코딩된 %XX 는 그대로 두고, URI 에 쓸 수 없는 문자만 UTF-8 바이트 단위로 %XX 로 바꾼다.
    static String encodeIllegal(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && allowed((char) c)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static boolean allowed(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~!$&'()*+,;=:@/?%".indexOf(c) >= 0;
    }

    private static void error(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getOutputStream().write(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
