package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 대시보드 기능요청 게시판 글 한 건. done 토글 시 completedAt 을 함께 관리한다.
@Entity
@Table(name = "feature_requests")
public class FeatureRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String author;

    @Column(nullable = false)
    private boolean done;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    protected FeatureRequestEntity() {}

    public FeatureRequestEntity(String title, String content, String author) {
        this.title = title;
        this.content = content;
        this.author = author;
        this.done = false;
        this.createdAt = Instant.now();
    }

    public void setDone(boolean done) {
        this.done = done;
        this.completedAt = done ? Instant.now() : null;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getAuthor() { return author; }
    public boolean isDone() { return done; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
