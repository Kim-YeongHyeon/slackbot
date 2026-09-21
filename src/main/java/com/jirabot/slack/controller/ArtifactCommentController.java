package com.jirabot.slack.controller;

import com.jirabot.slack.entity.ArtifactCommentEntity;
import com.jirabot.slack.repository.ArtifactCommentRepository;
import com.jirabot.slack.repository.ArtifactRepository;
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

// STUDY: 아티팩트 인라인 댓글 CRUD API (v0.0.75). FeatureRequestController 처럼 서비스 레이어 없이
//        컨트롤러가 repo 를 직접 쓴다. 보안/프록시는 기존 /api/artifacts/** matcher·Go mount 가 커버.
//        루트 댓글만 앵커(quote/prefix/suffix)와 resolved 를 갖고, 대댓글(parentId)은 앵커 NULL·1단계만.
@RestController
@RequestMapping("/api/artifacts/{artifactId}/comments")
public class ArtifactCommentController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCommentController.class);
    static final int MAX_BODY = 4000;
    static final int MAX_QUOTE = 500;
    static final int MAX_AFFIX = 64;
    static final int MAX_AUTHOR = 100;

    // ArtifactController 와 동일한 컨트롤러 내부 검증 예외 — 400 {error} 로 변환. (컨트롤러별 핸들러)
    static class BadRequest extends RuntimeException {
        BadRequest(String message) { super(message); }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<Object> onBadRequest(BadRequest e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private final ArtifactRepository artifactRepository;
    private final ArtifactCommentRepository commentRepository;

    public ArtifactCommentController(ArtifactRepository artifactRepository,
                                     ArtifactCommentRepository commentRepository) {
        this.artifactRepository = artifactRepository;
        this.commentRepository = commentRepository;
    }

    // 전체 목록 (resolved 포함 — 클라이언트가 필터). 아티팩트 없으면 404.
    @GetMapping
    public ResponseEntity<Object> list(@PathVariable long artifactId) {
        if (!artifactRepository.existsById(artifactId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(commentRepository.findByArtifactIdOrderByCreatedAtAscIdAsc(artifactId));
    }

    // 댓글/대댓글 등록. 대댓글이면 앵커 필드는 입력과 무관하게 null 로 저장 (루트 앵커 상속).
    @PostMapping
    public ResponseEntity<Object> create(@PathVariable long artifactId,
                                         @RequestBody Map<String, Object> body) {
        if (!artifactRepository.existsById(artifactId)) {
            return ResponseEntity.notFound().build();
        }
        String text = str(body.get("body"));
        if (text == null || text.isBlank()) {
            throw new BadRequest("body 내용이 비어 있습니다.");
        }
        if (text.length() > MAX_BODY) {
            throw new BadRequest("댓글이 너무 깁니다 (" + MAX_BODY + "자 이내).");
        }
        String author = trimTo(str(body.get("author")), MAX_AUTHOR);
        Long parentId = coerceLong(body.get("parentId"));

        String quote;
        String prefix;
        String suffix;
        if (parentId != null) {
            // 대댓글: 부모는 존재·같은 아티팩트·루트여야 한다 (1단계 스레드만). 앵커는 무조건 null.
            ArtifactCommentEntity parent = commentRepository.findById(parentId).orElse(null);
            if (parent == null || parent.getArtifactId() != artifactId || parent.getParentId() != null) {
                throw new BadRequest("잘못된 부모 댓글입니다.");
            }
            quote = null;
            prefix = null;
            suffix = null;
        } else {
            // 앵커는 자르지 않고 초과 시 거부 — 재앵커 정확도를 위해 원문 그대로여야 한다.
            quote = blankToNull(str(body.get("quote")));
            prefix = str(body.get("prefix"));
            suffix = str(body.get("suffix"));
            if (quote != null && quote.length() > MAX_QUOTE) {
                throw new BadRequest("인용문이 너무 깁니다 (" + MAX_QUOTE + "자 이내).");
            }
            if (prefix != null && prefix.length() > MAX_AFFIX) {
                throw new BadRequest("prefix 가 너무 깁니다 (" + MAX_AFFIX + "자 이내).");
            }
            if (suffix != null && suffix.length() > MAX_AFFIX) {
                throw new BadRequest("suffix 가 너무 깁니다 (" + MAX_AFFIX + "자 이내).");
            }
        }

        ArtifactCommentEntity saved = commentRepository.save(new ArtifactCommentEntity(
                artifactId, parentId, author, text, quote, prefix, suffix));
        log.info("Artifact comment created id={} artifact={} parent={}",
                saved.getId(), artifactId, parentId);
        return ResponseEntity.ok(saved);
    }

    // 완료/되돌리기 토글 (루트 전용). 대댓글이면 400.
    @PatchMapping("/{commentId}")
    public ResponseEntity<Object> patch(@PathVariable long artifactId,
                                        @PathVariable long commentId,
                                        @RequestBody Map<String, Object> body) {
        ArtifactCommentEntity comment = commentRepository.findById(commentId).orElse(null);
        if (comment == null || comment.getArtifactId() != artifactId) {
            return ResponseEntity.notFound().build();
        }
        if (comment.getParentId() != null) {
            throw new BadRequest("대댓글에는 완료를 설정할 수 없습니다.");
        }
        if (body.get("resolved") instanceof Boolean resolved) {
            comment.setResolved(resolved);
            commentRepository.save(comment);
        }
        return ResponseEntity.ok(comment);
    }

    // 삭제. 루트 삭제 시 대댓글은 DB ON DELETE CASCADE 로 함께 삭제.
    @DeleteMapping("/{commentId}")
    public ResponseEntity<Object> delete(@PathVariable long artifactId, @PathVariable long commentId) {
        ArtifactCommentEntity comment = commentRepository.findById(commentId).orElse(null);
        if (comment == null || comment.getArtifactId() != artifactId) {
            return ResponseEntity.notFound().build();
        }
        commentRepository.deleteById(commentId);
        log.info("Artifact comment deleted id={} artifact={}", commentId, artifactId);
        return ResponseEntity.ok(Map.of("deleted", commentId));
    }

    // ===== 헬퍼 =====

    // JSON Map 값을 문자열로 (숫자 등 비문자열은 무시하고 null). body/author/quote/prefix/suffix 용.
    private static String str(Object v) {
        return (v instanceof String s) ? s : null;
    }

    // 빈/공백 문자열은 null 로 — 인용문 없는 댓글의 quote 를 정규화. (prefix/suffix 는 앞뒤 공백이
    // 재앵커에 의미가 있어 그대로 두고, 빈 문자열이면 아래 str 이 그대로 반환한다.)
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // parentId 는 JSON Map 에서 Integer/Long/String 아무 형태로 올 수 있어 견고하게 강제 변환.
    private static Long coerceLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new BadRequest("parentId 형식이 잘못되었습니다.");
            }
        }
        return null;
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }
}
