package com.jirabot.slack.repository;

import com.jirabot.slack.entity.FeatureRequestEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureRequestRepository extends JpaRepository<FeatureRequestEntity, Long> {

    List<FeatureRequestEntity> findAllByOrderByCreatedAtDesc();
}
