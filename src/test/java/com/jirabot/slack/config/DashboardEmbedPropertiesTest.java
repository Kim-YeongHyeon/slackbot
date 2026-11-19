package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.config.DashboardEmbedProperties.Tab;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardEmbedPropertiesTest {

    @Test
    void parsesLabelAndPathPairsInOrder() {
        var props = new DashboardEmbedProperties(" 모니터링 | /grafana/d/a?kiosk ; 서버|/grafana/d/b ", null);

        assertThat(props.parsedTabs()).containsExactly(
                new Tab("embed-0", "모니터링", "/grafana/d/a?kiosk"),
                new Tab("embed-1", "서버", "/grafana/d/b"));
    }

    @Test
    void emptyOrMissing_noTabs() {
        assertThat(new DashboardEmbedProperties(null, null).parsedTabs()).isEmpty();
        assertThat(new DashboardEmbedProperties("  ", null).parsedTabs()).isEmpty();
        assertThat(new DashboardEmbedProperties(";;", null).parsedTabs()).isEmpty();
    }

    @Test
    void otherOriginsAndUnsafePaths_skipped_validOnesKept() {
        var props = new DashboardEmbedProperties(String.join(";",
                "a|//evil.example/x",          // 프로토콜 상대 URL → 다른 출처
                "b|https://evil.example/",     // 스킴
                "c|grafana/d/x",               // / 로 시작하지 않음
                "d|/\\evil.example",           // 역슬래시
                "e|/x?y=javascript:alert(1)",  // 스킴 문자열
                "f|/has space",                // 공백
                "ok|/grafana/d/ok"), null);

        assertThat(props.parsedTabs()).extracting(Tab::label).containsExactly("ok");
        // id 는 남은 항목 기준으로 0 부터 — 프런트의 panel id 와 맞물린다.
        assertThat(props.parsedTabs().get(0).id()).isEqualTo("embed-0");
    }

    @Test
    void missingOrTooLongLabel_skipped() {
        var props = new DashboardEmbedProperties(
                "/grafana/d/nolabel;|/grafana/d/blank;" + "x".repeat(31) + "|/grafana/d/long", null);

        assertThat(props.parsedTabs()).isEmpty();
    }

    @Test
    void atMostTenTabs() {
        String raw = String.join(";", java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "t" + i + "|/p/" + i).toList());

        List<Tab> tabs = new DashboardEmbedProperties(raw, null).parsedTabs();

        assertThat(tabs).hasSize(10);
        assertThat(tabs.get(9).label()).isEqualTo("t9");
    }

    @Test
    void grafanaUrl_trimmedAndTrailingSlashRemoved() {
        var props = new DashboardEmbedProperties(null, " http://127.0.0.1:3001/ ");

        assertThat(props.grafanaEnabled()).isTrue();
        assertThat(props.grafanaUrl()).isEqualTo("http://127.0.0.1:3001");
    }

    @Test
    void grafanaUrl_blank_disabled() {
        assertThat(new DashboardEmbedProperties(null, "  ").grafanaEnabled()).isFalse();
        assertThat(new DashboardEmbedProperties(null, null).grafanaEnabled()).isFalse();
    }
}
