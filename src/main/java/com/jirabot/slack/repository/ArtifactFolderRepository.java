package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ArtifactFolderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactFolderRepository extends JpaRepository<ArtifactFolderEntity, Long> {

    List<ArtifactFolderEntity> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    // 이름 변경 시 자기 자신은 중복으로 치지 않는다
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
