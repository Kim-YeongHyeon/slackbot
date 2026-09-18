package com.jirabot.slack.controller;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import com.jirabot.slack.entity.ArtifactEntity;
import com.jirabot.slack.repository.ArtifactAssetRepository;
import com.jirabot.slack.repository.ArtifactRepository;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// STUDY: 공개 HTML 아티팩트 갤러리 (v0.0.71, 자산 디렉토리 v0.0.72). 관리 API(/api/artifacts —
//        목록/업로드/삭제)는 Go 프록시의 Basic Auth 뒤, 뷰어(/artifacts/view/{id}/)는 무인증
//        공개(링크 공유용). FeatureRequestController 와 같은 단순 CRUD 패턴 — 서비스 레이어 없음.
@RestController
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);
    static final int MAX_TITLE = 200;
    static final int MAX_HTML_BYTES = 5 * 1024 * 1024; // 5MB
    static final int MAX_ASSET_BYTES = 5 * 1024 * 1024; // 파일당 5MB
    static final long MAX_TOTAL_ASSET_BYTES = 25L * 1024 * 1024; // 아티팩트당 자산 합계 25MB
    static final int MAX_ASSET_COUNT = 200;
    static final int MAX_ASSET_PATH = 500;

    private static final Pattern HTML_TITLE = Pattern.compile(
            "<title[^>]*>([^<]{1,200})</title>", Pattern.CASE_INSENSITIVE);

    private final ArtifactRepository repository;
    private final ArtifactAssetRepository assetRepository;

    public ArtifactController(ArtifactRepository repository, ArtifactAssetRepository assetRepository) {
        this.repository = repository;
        this.assetRepository = assetRepository;
    }

    @GetMapping("/api/artifacts")
    public List<ArtifactRepository.ArtifactSummary> list() {
        return repository.findAllSummaries();
    }

    // 붙여넣기 모드/기존 클라이언트용 JSON 업로드 (자산 없음). consumes 명시로 멀티파트 핸들러와 분기.
    @PostMapping(value = "/api/artifacts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> create(@RequestBody Map<String, String> body) {
        String html = body.get("html");
        ResponseEntity<Object> invalid = validateHtml(html);
        if (invalid != null) {
            return invalid;
        }
        String title = resolveTitle(body.get("title"), html, body.get("filename"));
        String author = trimTo(body.get("author"), 100);

        ArtifactEntity saved = repository.save(new ArtifactEntity(title, author, html));
        log.info("Artifact created id={} title='{}' size={}B author={}", saved.getId(), title,
                html.getBytes(StandardCharsets.UTF_8).length, author);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "title", title));
    }

    // 디렉토리(자산) 동반 업로드 (v0.0.72). 자산 경로는 part filename 이 아닌 반복 assetPaths
    // 텍스트 필드로 받는다 — filename 의 슬래시 보존은 서블릿 구현 의존적(스펙상 base name 만
    // 반환해도 됨)이고 한글 폴더명 인코딩도 불안정하지만, 텍스트 필드는 UTF-8 로 결정적이다.
    // STUDY: @Transactional — 아티팩트 1건 + 자산 N건이 별도 save 라 중간 실패 시 반쪽 업로드가
    //        남지 않도록 하나의 트랜잭션으로 묶는다.
    @PostMapping(value = "/api/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Object> createMultipart(
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "html", required = false) String html,
            @RequestParam(value = "assets", required = false) List<MultipartFile> assets,
            @RequestParam(value = "assetPaths", required = false) List<String> assetPaths) throws IOException {
        ResponseEntity<Object> invalid = validateHtml(html);
        if (invalid != null) {
            return invalid;
        }
        List<MultipartFile> files = assets == null ? List.of() : assets;
        List<String> paths = assetPaths == null ? List.of() : assetPaths;
        if (files.size() != paths.size()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    String.format("assets(%d)와 assetPaths(%d) 개수가 다릅니다.", files.size(), paths.size())));
        }
        if (files.size() > MAX_ASSET_COUNT) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    String.format("자산 파일이 너무 많습니다 (%d개 > %d개 제한).", files.size(), MAX_ASSET_COUNT)));
        }
        long totalBytes = 0;
        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>(paths.size());
        for (int i = 0; i < files.size(); i++) {
            long size = files.get(i).getSize();
            if (size > MAX_ASSET_BYTES) {
                return ResponseEntity.badRequest().body(Map.of("error", String.format(
                        "자산 파일이 너무 큽니다: %s (%.1fMB > 5MB 제한).", paths.get(i), size / 1024.0 / 1024.0)));
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_ASSET_BYTES) {
                return ResponseEntity.badRequest().body(Map.of("error", "자산 합계가 25MB 제한을 초과했습니다."));
            }
            String path = normalizeAssetPath(paths.get(i));
            if (path == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "잘못된 자산 경로입니다: " + paths.get(i)));
            }
            if (!seen.add(path)) {
                return ResponseEntity.badRequest().body(Map.of("error", "자산 경로가 중복됩니다: " + path));
            }
            normalized.add(path);
        }

        String resolvedTitle = resolveTitle(title, html, filename);
        ArtifactEntity saved = repository.save(
                new ArtifactEntity(resolvedTitle, trimTo(author, 100), html));

        List<ArtifactAssetEntity> assetEntities = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            // STUDY: MediaTypeFactory — spring-web 내장 mime.types 로 확장자→MIME 매핑 (OS 무의존).
            //        클라이언트가 보낸 part content-type 은 신뢰하지 않는다.
            String contentType = MediaTypeFactory.getMediaType(normalized.get(i))
                    .orElse(MediaType.APPLICATION_OCTET_STREAM).toString();
            assetEntities.add(new ArtifactAssetEntity(
                    saved.getId(), normalized.get(i), contentType, files.get(i).getBytes()));
        }
        assetRepository.saveAll(assetEntities);
        log.info("Artifact created id={} title='{}' htmlSize={}B assets={} assetBytes={}B",
                saved.getId(), resolvedTitle, html.getBytes(StandardCharsets.UTF_8).length,
                files.size(), totalBytes);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "title", resolvedTitle,
                "assetCount", files.size()));
    }

    @DeleteMapping("/api/artifacts/{id}")
    public ResponseEntity<Object> delete(@PathVariable long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id); // 자산은 DB ON DELETE CASCADE 로 함께 삭제
        log.info("Artifact deleted id={}", id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // 상대 자산 참조(page_files/x.png)가 {id}/ 기준으로 풀리도록 정식 URL 은 trailing slash.
    // 구 공유 링크(/artifacts/view/2)는 여기서 301 — PathPattern 구체성 비교로 캐치올보다 우선.
    @GetMapping("/artifacts/view/{id}")
    public ResponseEntity<Void> redirectToDirForm(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create("/artifacts/view/" + id + "/"))
                .build();
    }

    // STUDY: 공개 뷰어 — 업로드된 HTML/자산을 raw 서빙. 같은 origin 이므로 저장형 XSS 가 대시보드
    //        API 를 공격할 수 있어 CSP `sandbox allow-scripts` 로 서빙한다: 문서가 고유(opaque)
    //        origin 이 되어 same-origin API 접근·폼 제출이 차단되고, 스크립트(차트 등)는 동작한다.
    //        자산도 동일 헤더 — 직접 열리는 HTML/SVG 자산 역시 opaque origin 이어야 한다. 방어 축소 금지.
    // STUDY: {*path} — PathPattern 의 capture-the-rest. "/artifacts/view/2/" 는 path="/",
    //        "/artifacts/view/2/a/b.png" 는 path="/a/b.png" (선행 슬래시 포함) 로 바인딩된다.
    //        produces 는 걸지 않는다 — iframe/img 서브리소스의 Accept 헤더로 406 나는 것 방지.
    @GetMapping("/artifacts/view/{id}/{*path}")
    public ResponseEntity<Object> view(@PathVariable long id, @PathVariable String path) {
        if (path.isEmpty() || "/".equals(path)) {
            return repository.findById(id)
                    .<ResponseEntity<Object>>map(a -> ResponseEntity.ok()
                            .header("Content-Security-Policy", "sandbox allow-scripts")
                            .header("X-Content-Type-Options", "nosniff")
                            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                            .body(a.getHtml()))
                    .orElseGet(() -> ResponseEntity.status(404)
                            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                            .body("<html><body style='font-family:sans-serif'>"
                                    + "<h3>아티팩트를 찾을 수 없습니다</h3><p>삭제되었거나 잘못된 링크입니다.</p></body></html>"));
        }
        String assetPath = path.substring(1);
        return assetRepository.findByArtifactIdAndPath(id, assetPath)
                .<ResponseEntity<Object>>map(asset -> ResponseEntity.ok()
                        .header("Content-Security-Policy", "sandbox allow-scripts")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Cache-Control", "public, max-age=3600")
                        .contentType(MediaType.parseMediaType(asset.getContentType()))
                        .body(asset.getData()))
                .orElseGet(() -> ResponseEntity.status(404)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("asset not found"));
    }

    // html 공통 검증 — 문제 없으면 null, 있으면 400 응답 반환
    private static ResponseEntity<Object> validateHtml(String html) {
        if (html == null || html.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "html 내용이 비어 있습니다."));
        }
        int bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_HTML_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    String.format("HTML이 너무 큽니다 (%.1fMB > 5MB 제한).", bytes / 1024.0 / 1024.0)));
        }
        return null;
    }

    // 자산 경로 정규화. 유효하지 않으면 null.
    // 보안 경계는 아니다 — 조회가 DB (artifact_id, path) 정확 일치라 traversal 이 원천 무해하고,
    // 저장 경로가 브라우저의 실제 요청 경로와 일치하도록 맞추는 정합성 목적이다.
    static String normalizeAssetPath(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.strip();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.isEmpty() || p.length() > MAX_ASSET_PATH || p.startsWith("/") || p.contains("\\")) {
            return null;
        }
        for (char c : p.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        for (String segment : p.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return p;
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
