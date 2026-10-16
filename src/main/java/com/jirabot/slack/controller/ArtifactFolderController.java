package com.jirabot.slack.controller;

import com.jirabot.slack.entity.ArtifactFolderEntity;
import com.jirabot.slack.repository.ArtifactFolderRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// STUDY: 아티팩트 주제 폴더 CRUD (v0.0.86). 만들기·이름 변경은 로그인한 누구나, 삭제는 관리자만 —
//        삭제 권한은 SecurityConfig 의 HttpMethod.DELETE 규칙이 담당(컨트롤러에 도달 전 403).
//        삭제 시 안의 아티팩트는 DB FK(ON DELETE SET NULL)가 미분류로 돌린다 — 코드 처리 불필요.
@RestController
@RequestMapping("/api/artifact-folders")
public class ArtifactFolderController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactFolderController.class);
    static final int MAX_NAME = 50;

    static class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<Object> onBadRequest(BadRequest e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private final ArtifactFolderRepository repository;

    public ArtifactFolderController(ArtifactFolderRepository repository) {
        this.repository = repository;
    }

    // 폴더별 아티팩트 개수는 프론트가 /api/artifacts 목록(folderId)으로 계산한다 — 여기선 목록만.
    @GetMapping
    public List<ArtifactFolderEntity> list() {
        return repository.findAllByOrderByNameAsc();
    }

    @PostMapping
    public ArtifactFolderEntity create(@RequestBody Map<String, Object> body) {
        String name = requireName(body.get("name"));
        if (repository.existsByNameIgnoreCase(name)) {
            throw new BadRequest("이미 있는 폴더 이름입니다: " + name);
        }
        ArtifactFolderEntity saved = repository.save(new ArtifactFolderEntity(name));
        log.info("Artifact folder created id={} name='{}'", saved.getId(), name);
        return saved;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Object> rename(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ArtifactFolderEntity folder = repository.findById(id).orElse(null);
        if (folder == null) {
            return ResponseEntity.notFound().build();
        }
        String name = requireName(body.get("name"));
        if (repository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new BadRequest("이미 있는 폴더 이름입니다: " + name);
        }
        folder.setName(name);
        log.info("Artifact folder renamed id={} name='{}'", id, name);
        return ResponseEntity.ok(repository.save(folder));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        log.info("Artifact folder deleted id={} (artifacts → 미분류 via FK)", id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    private static String requireName(Object raw) {
        String name = raw instanceof String s ? s.strip() : "";
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BadRequest("폴더 이름을 1~" + MAX_NAME + "자로 입력해주세요.");
        }
        return name;
    }
}
