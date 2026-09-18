package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactAssetRepository extends JpaRepository<ArtifactAssetEntity, Long> {

    Optional<ArtifactAssetEntity> findByArtifactIdAndPath(long artifactId, String path);
}
