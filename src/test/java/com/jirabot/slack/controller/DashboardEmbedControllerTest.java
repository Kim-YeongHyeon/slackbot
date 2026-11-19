package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.config.DashboardEmbedProperties;
import com.jirabot.slack.config.DashboardEmbedProperties.Tab;
import org.junit.jupiter.api.Test;

class DashboardEmbedControllerTest {

    @Test
    void returnsConfiguredTabs() {
        var controller = new DashboardEmbedController(
                new DashboardEmbedProperties("모니터링|/grafana/d/a?kiosk", null));

        assertThat(controller.embedTabs()).containsExactly(new Tab("embed-0", "모니터링", "/grafana/d/a?kiosk"));
    }

    @Test
    void noConfig_emptyList() {
        assertThat(new DashboardEmbedController(new DashboardEmbedProperties(null, null)).embedTabs()).isEmpty();
    }

    @Test
    void onlyInvalidEntries_emptyList() {
        var controller = new DashboardEmbedController(
                new DashboardEmbedProperties("밖|https://example.com/", null));

        assertThat(controller.embedTabs()).isEmpty();
    }
}
