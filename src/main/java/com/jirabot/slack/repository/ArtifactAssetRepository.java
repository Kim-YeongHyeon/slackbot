package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactAssetRepository extends JpaRepository<ArtifactAssetEntity, Long> {

    Optional<ArtifactAssetEntity> findByArtifactIdAndPath(long artifactId, String path);

    // STUDY: 파생 deleteBy 가 아닌 @Modifying 벌크 삭제 — 파생 삭제는 엔티티를 로드해 flush 시점에
    //        지우는데, Hibernate 는 flush 에서 INSERT 를 DELETE 보다 먼저 실행하므로 "삭제 후 같은
    //        경로 재삽입"(아티팩트 수정)이 UNIQUE(artifact_id, path) 위반으로 터진다.
    //        벌크 JPQL 은 즉시 실행되어 순서가 보장된다.
    @Modifying
    @Query("DELETE FROM ArtifactAssetEntity s WHERE s.artifactId = :artifactId")
    void deleteByArtifactId(@Param("artifactId") long artifactId);
}
