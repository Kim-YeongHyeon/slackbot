package com.jirabot.slack.controller;

import com.jirabot.slack.config.JiraWebhookProperties;
import com.jirabot.slack.service.JiraWebhookService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// STUDY: Jira webhook 수신 엔드포인트.
//        인증은 쿼리 파라미터 ?token=... + MessageDigest.isEqual (SlackSignatureFilter 와 동일한 timing-safe 비교).
//        본 작업 범위 단일 엔드포인트라 별도 Filter 대신 컨트롤러 안에서 처리한다.
@RestController
@RequestMapping(path = "/api/jira", produces = MediaType.APPLICATION_JSON_VALUE)
public class JiraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(JiraWebhookController.class);

    private final JiraWebhookProperties props;
    private final JiraWebhookService service;

    public JiraWebhookController(JiraWebhookProperties props, JiraWebhookService service) {
        this.props = props;
        this.service = service;
    }

    @PostMapping(path = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> onWebhook(@RequestParam(name = "token", required = false) String token,
                                                         @RequestBody String body) {
        if (!props.enabled()) {
            log.warn("Webhook rejected: jira.webhook.enabled=false");
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "disabled"));
        }
        if (!verifyToken(token)) {
            log.warn("Webhook rejected: token mismatch");
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "forbidden"));
        }

        service.process(body);
        // STUDY: 인증 통과 후에는 페이로드 파싱·처리 실패와 무관하게 항상 200. Jira 재시도 폭주 방지.
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean verifyToken(String token) {
        String configured = props.secret();
        if (configured == null || configured.isBlank()) return false;
        if (token == null || token.isBlank()) return false;
        byte[] a = token.getBytes(StandardCharsets.UTF_8);
        byte[] b = configured.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        return MessageDigest.isEqual(a, b);
    }
}
