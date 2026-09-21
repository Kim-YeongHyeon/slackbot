package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ArtifactCommentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactCommentRepository extends JpaRepository<ArtifactCommentEntity, Long> {

    // 한 아티팩트의 모든 댓글(루트+대댓글)을 오래된 순으로. resolved 포함 — 클라이언트가 필터.
    // 같은 시각(created_at) 동률은 id 로 안정 정렬해 대댓글 순서가 흔들리지 않게 한다.
    List<ArtifactCommentEntity> findByArtifactIdOrderByCreatedAtAscIdAsc(long artifactId);
}
