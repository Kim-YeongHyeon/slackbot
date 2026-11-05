package com.jirabot.slack.controller;

import com.jirabot.slack.cialert.CiAlertService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: #sol-sbom-ci 알림 재처리 (v0.0.91, 관리자 전용 — SecurityConfig 의 /api/admin/** 규칙).
//        {"ts": "<알림 ts>", "dryRunUser": "<Slack user id>"} — dryRunUser 를 주면 팀 채널 대신 그 사람 DM 으로만
//        결과를 보낸다. 과거 알림으로 분석 품질을 검증하거나, 실패한 알림을 다시 돌릴 때 쓴다.
@RestController
@RequestMapping("/api/admin/ci-alerts")
public class CiAlertAdminController {

    private final CiAlertService service;

    public CiAlertAdminController(CiAlertService service) {
        this.service = service;
    }

    @PostMapping("/replay")
    public ResponseEntity<Object> replay(@RequestBody Map<String, Object> body) {
        String ts = body.get("ts") instanceof String s ? s.strip() : "";
        String dryRunUser = body.get("dryRunUser") instanceof String s ? s.strip() : null;
        if (!ts.matches("\\d+\\.\\d+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "ts 를 Slack 메시지 ts 형식(예: 1793345678.123456)으로 주세요"));
        }
        try {
            long id = service.replay(ts, dryRunUser);
            return ResponseEntity.accepted().body(Map.of("queued", true, "alertId", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}
