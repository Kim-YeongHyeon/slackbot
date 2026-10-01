package com.jirabot.slack.repository;

import com.jirabot.slack.entity.DashboardUserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DashboardUserRepository extends JpaRepository<DashboardUserEntity, Long> {

    Optional<DashboardUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    List<DashboardUserEntity> findAllByOrderByCreatedAtAsc();
}
