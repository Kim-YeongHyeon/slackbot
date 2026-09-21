package com.jirabot.slack.controller;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import com.jirabot.slack.entity.ArtifactEntity;
import com.jirabot.slack.repository.ArtifactAssetRepository;
import com.jirabot.slack.repository.ArtifactRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.ClassPathResource;

// STUDY: HTML 아티팩트 갤러리 (v0.0.71~). 목록/업로드/수정/삭제(/api/artifacts)와
//        뷰어(/artifacts/view/{id}/)는 v0.0.73 부터 전부 로그인(httpBasic) 필수 — SecurityConfig 참고.
//        FeatureRequestController 와 같은 단순 CRUD 패턴 — 서비스 레이어 없음.
@RestController
public class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);
    static final int MAX_TITLE = 200;
    static final int MAX_HTML_BYTES = 5 * 1024 * 1024; // 5MB
    static final int MAX_ASSET_BYTES = 5 * 1024 * 1024; // 파일당 5MB
    static final long MAX_TOTAL_ASSET_BYTES = 25L * 1024 * 1024; // 아티팩트당 자산 합계 25MB
    static final int MAX_ASSET_COUNT = 200;
    static final int MAX_ASSET_PATH = 500;
    static final String DEFAULT_TITLE = "제목 없는 아티팩트";
    // 뷰어 하위 경로로 주입 에이전트를 서빙 (v0.0.75). 실제 자산과 충돌하지 않도록 "__" 접두.
    // 하위 경로라 RFC 7617 사전 인증전송으로 sandbox 문서에서도 자산과 동일 메커니즘으로 로드된다.
    static final String AGENT_PATH = "__comment-agent.js";

    private static final Pattern HTML_TITLE = Pattern.compile(
            "<title[^>]*>([^<]{1,200})</title>", Pattern.CASE_INSENSITIVE);

    // 검증 실패를 400 {error} 로 변환하는 컨트롤러 내부 예외 — POST/PUT 이 검증 로직을 공유하기 위함.
    // @Transactional 메서드에서 던지면 롤백도 함께 일어난다.
    static class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<Object> onBadRequest(BadRequest e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private final ArtifactRepository repository;
    private final ArtifactAssetRepository assetRepository;
    // classpath 리소스(static/ 아님)를 기동 시 1회 읽어 캐시 — 요청마다 파일 IO 를 하지 않는다.
    private final String agentJs;

    public ArtifactController(ArtifactRepository repository, ArtifactAssetRepository assetRepository) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        // STUDY: ClassPathResource — 클래스패스(빌드 시 jar 안)에서 리소스를 읽는다. static/ 밑에 두면
        //        Spring 이 정적 매핑으로도 노출하므로, 뷰어 핸들러가 유일한 서빙 경로가 되도록 밖에 둔다.
        try {
            this.agentJs = new String(new ClassPathResource("comment-agent.js")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 리소스 누락은 배포 사고 — checked IOException 을 unchecked 로 승격해 기동을 실패시킨다.
            throw new UncheckedIOException("comment-agent.js 리소스를 읽지 못했습니다", e);
        }
    }

    @GetMapping("/api/artifacts")
    public List<ArtifactRepository.ArtifactSummary> list() {
        return repository.findAllSummaries();
    }

    // 붙여넣기 모드/기존 클라이언트용 JSON 업로드 (자산 없음). consumes 명시로 멀티파트 핸들러와 분기.
    @PostMapping(value = "/api/artifacts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> create(@RequestBody Map<String, String> body) {
        String html = requireValidHtml(body.get("html"));
        String title = resolveTitle(body.get("title"), html, body.get("filename"), DEFAULT_TITLE);
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
        String validHtml = requireValidHtml(html);
        List<MultipartFile> files = assets == null ? List.of() : assets;
        List<String> normalized = validateAssets(files, assetPaths == null ? List.of() : assetPaths);

        String resolvedTitle = resolveTitle(title, validHtml, filename, DEFAULT_TITLE);
        ArtifactEntity saved = repository.save(
                new ArtifactEntity(resolvedTitle, trimTo(author, 100), validHtml));
        assetRepository.saveAll(toAssetEntities(saved.getId(), files, normalized));

        log.info("Artifact created id={} title='{}' htmlSize={}B assets={}",
                saved.getId(), resolvedTitle, validHtml.getBytes(StandardCharsets.UTF_8).length, files.size());
        return ResponseEntity.ok(Map.of("id", saved.getId(), "title", resolvedTitle,
                "assetCount", files.size()));
    }

    // 제자리 수정 (v0.0.74) — 같은 id/공유 링크를 유지한 채 내용 전체 교체 (PUT = full replace,
    // 자산도 보낸 것으로 통째 교체 — JSON 수정처럼 자산을 안 보내면 기존 자산은 삭제된다).
    @PutMapping(value = "/api/artifacts/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Object> update(@PathVariable long id, @RequestBody Map<String, String> body) {
        return repository.findById(id)
                .<ResponseEntity<Object>>map(artifact -> {
                    String html = requireValidHtml(body.get("html"));
                    applyUpdate(artifact, body.get("title"), body.get("filename"), body.get("author"), html);
                    assetRepository.deleteByArtifactId(id);
                    log.info("Artifact updated id={} title='{}' assets=0", id, artifact.getTitle());
                    return ResponseEntity.ok(Map.of("id", id, "title", artifact.getTitle(), "assetCount", 0));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/api/artifacts/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Object> updateMultipart(
            @PathVariable long id,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "html", required = false) String html,
            @RequestParam(value = "assets", required = false) List<MultipartFile> assets,
            @RequestParam(value = "assetPaths", required = false) List<String> assetPaths) throws IOException {
        ArtifactEntity artifact = repository.findById(id).orElse(null);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
        String validHtml = requireValidHtml(html);
        List<MultipartFile> files = assets == null ? List.of() : assets;
        List<String> normalized = validateAssets(files, assetPaths == null ? List.of() : assetPaths);

        applyUpdate(artifact, title, filename, author, validHtml);
        assetRepository.deleteByArtifactId(id); // 벌크 삭제 — saveAll 보다 먼저 실행됨 (repo STUDY 참고)
        assetRepository.saveAll(toAssetEntities(id, files, normalized));

        log.info("Artifact updated id={} title='{}' assets={}", id, artifact.getTitle(), files.size());
        return ResponseEntity.ok(Map.of("id", id, "title", artifact.getTitle(), "assetCount", files.size()));
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

    // STUDY: 뷰어 — 업로드된 HTML/자산을 raw 서빙. 같은 origin 이므로 저장형 XSS 가 대시보드
    //        API 를 공격할 수 있어 CSP `sandbox allow-scripts` 로 서빙한다: 문서가 고유(opaque)
    //        origin 이 되어 same-origin API 접근·폼 제출이 차단되고, 스크립트(차트 등)는 동작한다.
    //        자산도 동일 헤더 — 직접 열리는 HTML/SVG 자산 역시 opaque origin 이어야 한다. 방어 축소 금지.
    // STUDY: {*path} — PathPattern 의 capture-the-rest. "/artifacts/view/2/" 는 path="/",
    //        "/artifacts/view/2/a/b.png" 는 path="/a/b.png" (선행 슬래시 포함) 로 바인딩된다.
    //        produces 는 걸지 않는다 — iframe/img 서브리소스의 Accept 헤더로 406 나는 것 방지.
    //        자산에 Cache-Control 을 걸지 않는다(Security 기본 no-cache) — 제자리 수정(v0.0.74) 후
    //        구버전 자산이 보이는 것을 방지.
    @GetMapping("/artifacts/view/{id}/{*path}")
    public ResponseEntity<Object> view(@PathVariable long id, @PathVariable String path) {
        if (path.isEmpty() || "/".equals(path)) {
            return repository.findById(id)
                    // X-Frame-Options: SAMEORIGIN (v0.0.75) — Security 기본 DENY 가 review iframe 을
                    // 차단하던 것 + 카드 미리보기 잠복 버그(v0.0.71~)를 함께 해결. 문서 하단에 댓글
                    // 에이전트 <script> 를 주입해 sandbox 문서 안에서 선택/앵커/하이라이트를 담당시킨다.
                    .<ResponseEntity<Object>>map(a -> ResponseEntity.ok()
                            .header("Content-Security-Policy", "sandbox allow-scripts")
                            .header("X-Content-Type-Options", "nosniff")
                            .header("X-Frame-Options", "SAMEORIGIN")
                            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                            .body(injectAgent(a.getHtml(), id)))
                    .orElseGet(() -> ResponseEntity.status(404)
                            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                            .body("<html><body style='font-family:sans-serif'>"
                                    + "<h3>아티팩트를 찾을 수 없습니다</h3><p>삭제되었거나 잘못된 링크입니다.</p></body></html>"));
        }
        String assetPath = path.substring(1);
        // 댓글 에이전트 서빙 (v0.0.75) — 자산 repo 조회 *전에* 분기. 캐시된 classpath JS 를 돌려준다.
        // XFO SAMEORIGIN 은 안 붙인다 — 서브리소스(스크립트)라 프레이밍 대상이 아니다.
        if (AGENT_PATH.equals(assetPath)) {
            return ResponseEntity.ok()
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(new MediaType("text", "javascript", StandardCharsets.UTF_8))
                    .body(agentJs);
        }
        return assetRepository.findByArtifactIdAndPath(id, assetPath)
                .<ResponseEntity<Object>>map(asset -> ResponseEntity.ok()
                        .header("Content-Security-Policy", "sandbox allow-scripts")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("X-Frame-Options", "SAMEORIGIN")
                        .contentType(MediaType.parseMediaType(asset.getContentType()))
                        .body(asset.getData()))
                .orElseGet(() -> ResponseEntity.status(404)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("asset not found"));
    }

    // 리뷰 페이지 진입점 (v0.0.75) — 정적 SPA 로 302. 인증·일반 origin 페이지가 사이드바+API 를 담당.
    // {id:\d+} 정규식 필수 — 없으면 {id} 가 index.html/review.js 등 정적 파일명까지 매칭해
    // long 변환 실패(400)로 리뷰 페이지 자체가 안 열린다 (v0.0.75 라이브 검증에서 발견).
    @GetMapping("/artifacts/review/{id:\\d+}")
    public ResponseEntity<Void> reviewPage(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/artifacts/review/index.html?id=" + id))
                .build();
    }

    // </body> 마지막 매치 앞에 에이전트 <script defer> 를 주입 (없으면 문서 끝에 append).
    // STUDY: toLowerCase().lastIndexOf 는 터키어 로케일 등에서 'I'/'İ' 매핑이 깨진다(로케일 함정) —
    //        CASE_INSENSITIVE 정규식 Matcher 로 마지막 매치 위치를 안전하게 찾는다.
    static String injectAgent(String html, long id) {
        String tag = "<script src=\"/artifacts/view/" + id + "/" + AGENT_PATH + "\" defer></script>";
        Matcher m = Pattern.compile("</body>", Pattern.CASE_INSENSITIVE).matcher(html);
        int last = -1;
        while (m.find()) {
            last = m.start();
        }
        if (last < 0) {
            return html + tag;
        }
        return html.substring(0, last) + tag + html.substring(last);
    }

    // ===== 공용 검증/조립 =====

    // 수정 공통: 제목은 명시 → <title> → 파일명 → "기존 제목 유지", 작성자는 미지정 시 유지.
    private static void applyUpdate(ArtifactEntity artifact, String title, String filename,
                                    String author, String html) {
        String newTitle = resolveTitle(title, html, filename, artifact.getTitle());
        String newAuthor = (author == null || author.isBlank())
                ? artifact.getAuthor() : trimTo(author, 100);
        artifact.update(newTitle, newAuthor, html);
    }

    private static String requireValidHtml(String html) {
        if (html == null || html.isBlank()) {
            throw new BadRequest("html 내용이 비어 있습니다.");
        }
        int bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_HTML_BYTES) {
            throw new BadRequest(String.format("HTML이 너무 큽니다 (%.1fMB > 5MB 제한).",
                    bytes / 1024.0 / 1024.0));
        }
        return html;
    }

    // 개수/크기/경로 검증 후 정규화된 경로 목록 반환. 실패 시 BadRequest.
    private static List<String> validateAssets(List<MultipartFile> files, List<String> paths) {
        if (files.size() != paths.size()) {
            throw new BadRequest(String.format(
                    "assets(%d)와 assetPaths(%d) 개수가 다릅니다.", files.size(), paths.size()));
        }
        if (files.size() > MAX_ASSET_COUNT) {
            throw new BadRequest(String.format(
                    "자산 파일이 너무 많습니다 (%d개 > %d개 제한).", files.size(), MAX_ASSET_COUNT));
        }
        long totalBytes = 0;
        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>(paths.size());
        for (int i = 0; i < files.size(); i++) {
            long size = files.get(i).getSize();
            if (size > MAX_ASSET_BYTES) {
                throw new BadRequest(String.format("자산 파일이 너무 큽니다: %s (%.1fMB > 5MB 제한).",
                        paths.get(i), size / 1024.0 / 1024.0));
            }
            totalBytes += size;
            if (totalBytes > MAX_TOTAL_ASSET_BYTES) {
                throw new BadRequest("자산 합계가 25MB 제한을 초과했습니다.");
            }
            String path = normalizeAssetPath(paths.get(i));
            if (path == null) {
                throw new BadRequest("잘못된 자산 경로입니다: " + paths.get(i));
            }
            if (!seen.add(path)) {
                throw new BadRequest("자산 경로가 중복됩니다: " + path);
            }
            normalized.add(path);
        }
        return normalized;
    }

    private static List<ArtifactAssetEntity> toAssetEntities(
            long artifactId, List<MultipartFile> files, List<String> normalizedPaths) throws IOException {
        List<ArtifactAssetEntity> entities = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            // STUDY: MediaTypeFactory — spring-web 내장 mime.types 로 확장자→MIME 매핑 (OS 무의존).
            //        클라이언트가 보낸 part content-type 은 신뢰하지 않는다.
            String contentType = MediaTypeFactory.getMediaType(normalizedPaths.get(i))
                    .orElse(MediaType.APPLICATION_OCTET_STREAM).toString();
            entities.add(new ArtifactAssetEntity(
                    artifactId, normalizedPaths.get(i), contentType, files.get(i).getBytes()));
        }
        return entities;
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

    // 제목 결정: 명시 제목 → HTML <title> → 파일명 → fallback (생성: 기본 문구, 수정: 기존 제목)
    static String resolveTitle(String explicit, String html, String filename, String fallback) {
        String t = trimTo(explicit, MAX_TITLE);
        if (t != null && !t.isBlank()) return t;
        Matcher m = HTML_TITLE.matcher(html);
        if (m.find()) {
            String fromTag = m.group(1).strip();
            if (!fromTag.isBlank()) return trimTo(fromTag, MAX_TITLE);
        }
        String f = trimTo(filename, MAX_TITLE);
        if (f != null && !f.isBlank()) return f;
        return fallback;
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
