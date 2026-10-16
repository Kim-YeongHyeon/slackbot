package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 아티팩트 주제 폴더 (v0.0.86). 한 단계만 — 하위 폴더 없음. 아티팩트 쪽 artifacts.folder_id 가 참조.
@Entity
@Table(name = "artifact_folders")
public class ArtifactFolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ArtifactFolderEntity() {}

    public ArtifactFolderEntity(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
}
