package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ArtifactEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, Long> {

    // STUDY: interface projection — 목록에서 수 MB 짜리 html 컬럼을 로드하지 않기 위해
    //        필요한 컬럼만 select 한다 (Spring Data 가 getter 이름으로 매핑).
    interface ArtifactSummary {
        Long getId();
        String getTitle();
        String getAuthor();
        Instant getCreatedAt();
        int getSizeBytes();
        long getAssetCount();
    }

    @Query("SELECT a.id AS id, a.title AS title, a.author AS author, a.createdAt AS createdAt, "
            + "LENGTH(a.html) AS sizeBytes, "
            + "(SELECT COUNT(s) FROM ArtifactAssetEntity s WHERE s.artifactId = a.id) AS assetCount "
            + "FROM ArtifactEntity a ORDER BY a.createdAt DESC")
    List<ArtifactSummary> findAllSummaries();
}
