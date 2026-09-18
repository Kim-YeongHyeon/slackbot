package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 공개 HTML 아티팩트 한 건 (v0.0.71). html 은 뷰어(/artifacts/view/{id})에서 raw 서빙되므로
// 목록 조회 시엔 ArtifactRepository 의 projection 으로 제외한다 (수 MB 가능).
@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String author;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String html;

    @Column(nullable = false)
    private Instant createdAt;

    protected ArtifactEntity() {}

    public ArtifactEntity(String title, String author, String html) {
        this.title = title;
        this.author = author;
        this.html = html;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getHtml() { return html; }
    public Instant getCreatedAt() { return createdAt; }
}
