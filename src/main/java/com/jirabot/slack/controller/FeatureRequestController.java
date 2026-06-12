package com.jirabot.slack.controller;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.FeatureRequestEntity;
import com.jirabot.slack.repository.FeatureRequestRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 대시보드 기능요청 게시판 API. UserMappingController 와 같은 패턴 —
//        단순 CRUD 라 서비스 레이어 없이 컨트롤러가 repo + notifier 를 직접 쓴다.
//        등록 시 관리자(notify-slack-user)에게 DM. DM 실패는 non-fatal (글은 저장됨).
@RestController
@RequestMapping("/api/feature-requests")
public class FeatureRequestController {

    private static final Logger log = LoggerFactory.getLogger(FeatureRequestController.class);
    private static final int MAX_TITLE = 200;
    private static final int MAX_CONTENT = 4000;

    private final FeatureRequestRepository repository;
    private final SlackNotifier slackNotifier;
    private final String notifySlackUser;

    public FeatureRequestController(FeatureRequestRepository repository,
                                    SlackNotifier slackNotifier,
                                    @Value("${feature-request.notify-slack-user:}") String notifySlackUser) {
        this.repository = repository;
        this.slackNotifier = slackNotifier;
        this.notifySlackUser = notifySlackUser;
    }

    @GetMapping
    public List<FeatureRequestEntity> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, String> body) {
        String title = trimmed(body.get("title"));
        String content = trimmed(body.get("content"));
        String author = trimmed(body.get("author"));
        if (title == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        if (title.length() > MAX_TITLE || (content != null && content.length() > MAX_CONTENT)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "too long (title " + MAX_TITLE + "자, content " + MAX_CONTENT + "자 이내)"));
        }

        FeatureRequestEntity saved = repository.save(new FeatureRequestEntity(title, content, author));
        notifyAdmin(saved);
        return ResponseEntity.ok(saved);
    }

    // STUDY: 완료/되돌리기 토글. body 의 done 만 반영 (UserMappingController PATCH 와 동일 관례).
    @PatchMapping("/{id}")
    public ResponseEntity<Object> patch(@PathVariable("id") Long id,
                                        @RequestBody Map<String, Object> body) {
        var existing = repository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        FeatureRequestEntity entity = existing.get();
        if (body.get("done") instanceof Boolean done) {
            entity.setDone(done);
            repository.save(entity);
        }
        return ResponseEntity.ok(entity);
    }

    private void notifyAdmin(FeatureRequestEntity req) {
        if (notifySlackUser == null || notifySlackUser.isBlank()) {
            log.info("Feature request saved id={} — DM skipped (notify-slack-user unset)", req.getId());
            return;
        }
        try {
            String text = String.format(":bulb: *새 기능요청* — %s%n제목: %s%n%s%n대시보드 [기능요청] 탭에서 확인하세요.",
                    req.getAuthor() == null ? "익명" : req.getAuthor(),
                    req.getTitle(),
                    req.getContent() == null ? "(내용 없음)" : req.getContent());
            slackNotifier.sendDirectMessage(notifySlackUser, text);
            log.info("Feature request DM sent id={} to={}", req.getId(), notifySlackUser);
        } catch (Exception e) {
            log.warn("Feature request DM failed (non-fatal) id={}: {}", req.getId(), e.toString());
        }
    }

    private static String trimmed(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
