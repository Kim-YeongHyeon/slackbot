package com.jirabot.slack.repository;

import com.jirabot.slack.entity.ResponseMetricEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResponseMetricRepository extends JpaRepository<ResponseMetricEntity, Long> {

    // STUDY: Spring Data 의 TopN 파생 쿼리 — LIMIT 50 + ORDER BY 를 메서드명만으로 생성.
    List<ResponseMetricEntity> findTop50ByOrderByStartedAtDesc();

    List<ResponseMetricEntity> findByStartedAtAfter(Instant after);
}
