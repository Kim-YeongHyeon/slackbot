package com.jirabot.slack.controller;

import com.jirabot.slack.entity.ArtifactEntity;
import com.jirabot.slack.repository.ArtifactRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 공개 HTML 아티팩트 갤러리 (v0.0.71). 관리 API(/api/artifacts — 목록/업로드/삭제)는
//        Go 프록시의 Basic Auth 뒤, 뷰어(/artifacts/view/{id})는 무인증 공개(링크 공유용).
//        FeatureRequestController 와 같은 단순 CRUD 패턴 — 서비스 레이어 없음.
@RestController
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);
    static final int MAX_TITLE = 200;
    static final int MAX_HTML_BYTES = 5 * 1024 * 1024; // 5MB

    private static final Pattern HTML_TITLE = Pattern.compile(
            "<title[^>]*>([^<]{1,200})</title>", Pattern.CASE_INSENSITIVE);

    private final ArtifactRepository repository;

    public ArtifactController(ArtifactRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/artifacts")
    public List<ArtifactRepository.ArtifactSummary> list() {
        return repository.findAllSummaries();
    }

    @PostMapping("/api/artifacts")
    public ResponseEntity<Object> create(@RequestBody Map<String, String> body) {
        String html = body.get("html");
        if (html == null || html.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "html 내용이 비어 있습니다."));
        }
        int bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_HTML_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    String.format("HTML이 너무 큽니다 (%.1fMB > 5MB 제한).", bytes / 1024.0 / 1024.0)));
        }
        String title = resolveTitle(body.get("title"), html, body.get("filename"));
        String author = trimTo(body.get("author"), 100);

        ArtifactEntity saved = repository.save(new ArtifactEntity(title, author, html));
        log.info("Artifact created id={} title='{}' size={}B author={}", saved.getId(), title, bytes, author);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "title", title));
    }

    @DeleteMapping("/api/artifacts/{id}")
    public ResponseEntity<Object> delete(@PathVariable long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        log.info("Artifact deleted id={}", id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // STUDY: 공개 뷰어 — 업로드된 HTML 을 raw 서빙. 같은 origin 이므로 저장형 XSS 가 대시보드 API 를
    //        공격할 수 있어 CSP `sandbox allow-scripts` 로 서빙한다: 문서가 고유(opaque) origin 이 되어
    //        same-origin API 접근·폼 제출이 차단되고, 스크립트(차트 등)는 동작한다. 방어 축소 금지.
    @GetMapping(value = "/artifacts/view/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> view(@PathVariable long id) {
        return repository.findById(id)
                .map(a -> ResponseEntity.ok()
                        .header("Content-Security-Policy", "sandbox allow-scripts")
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .body(a.getHtml()))
                .orElseGet(() -> ResponseEntity.status(404)
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .body("<html><body style='font-family:sans-serif'>"
                                + "<h3>아티팩트를 찾을 수 없습니다</h3><p>삭제되었거나 잘못된 링크입니다.</p></body></html>"));
    }

    // 제목 결정: 명시 제목 → HTML <title> → 파일명 → "제목 없는 아티팩트"
    static String resolveTitle(String explicit, String html, String filename) {
        String t = trimTo(explicit, MAX_TITLE);
        if (t != null && !t.isBlank()) return t;
        Matcher m = HTML_TITLE.matcher(html);
        if (m.find()) {
            String fromTag = m.group(1).strip();
            if (!fromTag.isBlank()) return trimTo(fromTag, MAX_TITLE);
        }
        String f = trimTo(filename, MAX_TITLE);
        if (f != null && !f.isBlank()) return f;
        return "제목 없는 아티팩트";
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
