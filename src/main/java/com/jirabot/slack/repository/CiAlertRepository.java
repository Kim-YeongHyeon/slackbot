package com.jirabot.slack.repository;

import com.jirabot.slack.entity.CiAlertEntity;
import com.jirabot.slack.entity.CiAlertEntity.Status;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CiAlertRepository extends JpaRepository<CiAlertEntity, Long> {

    Optional<CiAlertEntity> findByChannelAndMessageTs(String channel, String messageTs);

    // 반복 알림 판단: 같은 채널(재처리 테스트는 "DM:<user>")·레포·지문으로 분석을 끝낸(DONE) 가장 최근 알림.
    // 자기 자신은 제외.
    Optional<CiAlertEntity> findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
            String channel, String repo, String fingerprint, Status status, Instant after, Long id);

    // 연속 실패 자동 수정 (v0.0.100): 같은 채널·워크플로(fix_key)에 수정 단계를 돌린 가장 최근 알림. 자기 자신은 제외.
    Optional<CiAlertEntity> findFirstByChannelAndFixKeyAndFixStatusIsNotNullAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
            String channel, String fixKey, Instant after, Long id);

    // STUDY: @Modifying 벌크 UPDATE — 재시작 전에 처리 중이던(PROCESSING) 알림을 FAILED 로 정리한다.
    //        영속성 컨텍스트를 거치지 않으므로 반환값(갱신 행 수)만 쓰고 엔티티 캐시에는 기대지 않는다.
    @Modifying
    @Transactional
    @Query("update CiAlertEntity a set a.status = :failed, a.error = :reason, a.updatedAt = :now "
            + "where a.status = :processing and a.updatedAt < :before")
    int failStale(@Param("processing") Status processing, @Param("failed") Status failed,
                  @Param("reason") String reason, @Param("now") Instant now, @Param("before") Instant before);
}
