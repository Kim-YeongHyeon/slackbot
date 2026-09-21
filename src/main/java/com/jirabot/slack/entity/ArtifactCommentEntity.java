package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 아티팩트 인라인 댓글 한 건 (v0.0.75) — Google Docs 스타일 드래그 코멘트.
// 앵커는 W3C TextQuoteSelector (quote + prefix/suffix 32자) — HTML 수정(PUT) 후 못 찾으면
// 사이드바에 '위치 없음' 고아 카드로 잔존. 대댓글(parentId != null)은 앵커 필드 NULL — 루트의
// 앵커를 상속하고 resolved 도 루트에서만 다룬다. 부모 artifacts 삭제 시 DB CASCADE.
// ArtifactAssetEntity 와 동일하게 평문 long FK 스타일 (@ManyToOne 없음).
@Entity
@Table(name = "artifact_comments")
public class ArtifactCommentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false)
    private long artifactId;

    // 대댓글이면 루트 댓글의 id, 루트면 null. 자기참조 FK 도 평문 Long 으로만 매핑 (연관 미사용).
    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 100)
    private String author;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(columnDefinition = "TEXT")
    private String quote;

    @Column(length = 64)
    private String prefix;

    @Column(length = 64)
    private String suffix;

    @Column(nullable = false)
    private boolean resolved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ArtifactCommentEntity() {}

    public ArtifactCommentEntity(long artifactId, Long parentId, String author, String body,
                                 String quote, String prefix, String suffix) {
        this.artifactId = artifactId;
        this.parentId = parentId;
        this.author = author;
        this.body = body;
        this.quote = quote;
        this.prefix = prefix;
        this.suffix = suffix;
        this.resolved = false;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getArtifactId() { return artifactId; }
    public Long getParentId() { return parentId; }
    public String getAuthor() { return author; }
    public String getBody() { return body; }
    public String getQuote() { return quote; }
    public String getPrefix() { return prefix; }
    public String getSuffix() { return suffix; }
    public boolean isResolved() { return resolved; }
    public Instant getCreatedAt() { return createdAt; }

    public void setResolved(boolean resolved) { this.resolved = resolved; }
}
