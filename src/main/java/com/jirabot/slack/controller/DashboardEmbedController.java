package com.jirabot.slack.controller;

import com.jirabot.slack.config.DashboardEmbedProperties;
import com.jirabot.slack.config.DashboardEmbedProperties.Tab;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 대시보드 임베드 탭 목록 (v0.0.104). 프런트가 시작할 때 받아 상위 탭과 iframe 패널을 만든다.
// /api/dashboard/** 라 로그인한 누구나 볼 수 있다. 설정이 비면 빈 배열 → 탭 없음.
@RestController
public class DashboardEmbedController {

    private static final Logger log = LoggerFactory.getLogger(DashboardEmbedController.class);

    private final List<Tab> tabs;

    // STUDY: 설정은 기동 때 한 번 읽어 고정한다(.env 를 바꾸면 재시작). 잘못된 항목 경고도 기동 로그에 한 번만 남는다.
    public DashboardEmbedController(DashboardEmbedProperties props) {
        this.tabs = List.copyOf(props.parsedTabs());
        if (!tabs.isEmpty()) {
            log.info("dashboard embed tabs: {}", tabs.stream().map(Tab::label).toList());
        }
    }

    @GetMapping("/api/dashboard/embed-tabs")
    public List<Tab> embedTabs() {
        return tabs;
    }
}
