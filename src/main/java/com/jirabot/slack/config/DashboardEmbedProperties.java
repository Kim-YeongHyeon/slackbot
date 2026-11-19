package com.jirabot.slack.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 대시보드 임베드 탭 (v0.0.104). 서버 .env 에서만 정한다 — 공개 레포에는 어떤 화면을 붙일지 들어가지 않는다.
//  - tabs: "라벨|/경로;라벨|/경로". 경로는 같은 출처만(`/` 로 시작, `//`·`\`·스킴 금지) — 외부 사이트를 iframe 으로
//    끌어오는 통로가 되지 않게 한다. 잘못된 항목은 경고 로그만 남기고 건너뛴다(기동은 막지 않는다).
//  - grafanaUrl: /grafana/** 를 넘길 Grafana 주소(예 http://127.0.0.1:3001). 비면 프록시는 404.
// STUDY: record 의 compact constructor 는 바인딩 직후 한 번 실행된다 — 기본값 정리를 여기서 한다.
@ConfigurationProperties(prefix = "dashboard.embed")
public record DashboardEmbedProperties(String tabs, String grafanaUrl) {

    private static final Logger log = LoggerFactory.getLogger(DashboardEmbedProperties.class);
    static final int MAX_LABEL = 30;
    static final int MAX_TABS = 10;

    public record Tab(String id, String label, String src) {}

    public DashboardEmbedProperties {
        tabs = tabs == null ? "" : tabs.strip();
        grafanaUrl = grafanaUrl == null ? "" : stripTrailingSlash(grafanaUrl.strip());
    }

    public boolean grafanaEnabled() {
        return !grafanaUrl.isEmpty();
    }

    public List<Tab> parsedTabs() {
        return parse(tabs);
    }

    static List<Tab> parse(String raw) {
        List<Tab> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String entry : raw.split(";")) {
            if (entry.isBlank()) continue;
            int bar = entry.indexOf('|');
            String label = bar < 0 ? "" : entry.substring(0, bar).strip();
            String src = bar < 0 ? entry.strip() : entry.substring(bar + 1).strip();
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                log.warn("dashboard.embed.tabs: 라벨이 비었거나 {}자를 넘어 건너뜀: {}", MAX_LABEL, entry.strip());
                continue;
            }
            if (!sameOriginPath(src)) {
                log.warn("dashboard.embed.tabs: 같은 출처 경로(/로 시작)가 아니어서 건너뜀: {}", entry.strip());
                continue;
            }
            if (out.size() == MAX_TABS) {
                log.warn("dashboard.embed.tabs: 탭은 최대 {}개 — 나머지는 건너뜀", MAX_TABS);
                break;
            }
            out.add(new Tab("embed-" + out.size(), label, src));
        }
        return out;
    }

    // STUDY: "//evil.example" 은 브라우저가 프로토콜 상대 URL 로 해석해 다른 출처가 된다. 역슬래시도 일부 브라우저가
    //        슬래시로 바꿔 읽으므로 같이 막는다. 제어 문자(줄바꿈 등)는 HTML 속성에 들어가지 않게 막는다.
    static boolean sameOriginPath(String src) {
        if (src == null || !src.startsWith("/") || src.startsWith("//")) return false;
        if (src.indexOf('\\') >= 0) return false;
        for (char c : src.toCharArray()) {
            if (Character.isISOControl(c) || Character.isWhitespace(c)) return false;
        }
        String lower = src.toLowerCase(Locale.ROOT);
        return !lower.contains("javascript:") && !lower.contains("data:");
    }

    private static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
