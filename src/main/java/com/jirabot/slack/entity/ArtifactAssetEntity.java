package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 아티팩트 하나에 딸린 자산 파일 한 개 (v0.0.72) — 저장된 웹페이지의 page_files/img.png 등.
// path 는 업로드 시 정규화된 상대 경로 그대로이며, 뷰어의 /artifacts/view/{id}/{path} 요청과
// 정확 일치로 조회된다. 부모 artifacts 행 삭제 시 DB CASCADE 로 함께 삭제.
@Entity
@Table(name = "artifact_assets")
public class ArtifactAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false)
    private long artifactId;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    // STUDY: @Lob 을 붙이면 Hibernate 6 이 Postgres 에서 bytea 가 아닌 oid(large object)로
    //        매핑해 ddl-auto: validate 가 실패하고 삭제 시 LO 행이 누수된다. 평문 byte[] = bytea.
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] data;

    protected ArtifactAssetEntity() {}

    public ArtifactAssetEntity(long artifactId, String path, String contentType, byte[] data) {
        this.artifactId = artifactId;
        this.path = path;
        this.contentType = contentType;
        this.data = data;
    }

    public Long getId() { return id; }
    public long getArtifactId() { return artifactId; }
    public String getPath() { return path; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
