package com.jirabot.slack.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jirabot.slack.entity.CiAlertEntity;
import com.jirabot.slack.entity.CiAlertEntity.Status;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

// ProcessedJiraChangelogRepositoryTest 와 같은 H2 컨텍스트. 클래스 레벨 @Transactional 없음 — 예외 후 조회가 깨진 세션에 걸리지 않게.
@SpringBootTest
@ActiveProfiles("test")
class CiAlertRepositoryTest {

    @Autowired
    private CiAlertRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void sameChannelAndTs_secondInsertViolatesUniqueConstraint() {
        repository.saveAndFlush(new CiAlertEntity("C1", "100.1"));

        assertThatThrownBy(() -> repository.saveAndFlush(new CiAlertEntity("C1", "100.1")))
                .isInstanceOf(DataIntegrityViolationException.class);
        repository.saveAndFlush(new CiAlertEntity("DM:U1", "100.1"));   // 채널이 다르면 허용
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void repeatLookup_findsLatestDoneInSameChannelExcludingSelf() {
        CiAlertEntity older = repository.saveAndFlush(new CiAlertEntity("C1", "1.0"));
        older.identify("evi", 1, "CI");
        older.finish(Status.DONE, "fp", "INFRA", "older", "r1");
        repository.saveAndFlush(older);
        CiAlertEntity failed = repository.saveAndFlush(new CiAlertEntity("C1", "2.0"));
        failed.identify("evi", 2, "CI");
        failed.finish(Status.FAILED, "fp", null, null, null);
        repository.saveAndFlush(failed);
        CiAlertEntity otherChannel = repository.saveAndFlush(new CiAlertEntity("DM:U1", "3.0"));
        otherChannel.identify("evi", 3, "CI");
        otherChannel.finish(Status.DONE, "fp", "INFRA", "dm", null);
        repository.saveAndFlush(otherChannel);
        CiAlertEntity current = repository.saveAndFlush(new CiAlertEntity("C1", "4.0"));

        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        var found = repository.findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                "C1", "evi", "fp", Status.DONE, since, current.getId());

        assertThat(found).get().extracting(CiAlertEntity::getSummary).isEqualTo("older");
        assertThat(repository.findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                "C1", "evi", "other-fp", Status.DONE, since, current.getId())).isEmpty();
        assertThat(repository.findFirstByChannelAndRepoAndFingerprintAndStatusAndCreatedAtAfterAndIdNotOrderByCreatedAtDesc(
                "C1", "evi", "fp", Status.DONE, Instant.now().plusSeconds(60), current.getId())).as("기간 밖").isEmpty();
    }

    @Test
    void failStale_onlyOldProcessingRows() {
        CiAlertEntity processing = repository.saveAndFlush(new CiAlertEntity("C1", "1.0"));
        CiAlertEntity done = repository.saveAndFlush(new CiAlertEntity("C1", "2.0"));
        done.finish(Status.DONE, "fp", "CODE", "s", null);
        repository.saveAndFlush(done);

        Instant now = Instant.now();
        assertThat(repository.failStale(Status.PROCESSING, Status.FAILED, "restart", now, now.minusSeconds(3600)))
                .as("방금 만든 행은 오래되지 않음").isZero();
        assertThat(repository.failStale(Status.PROCESSING, Status.FAILED, "restart", now, now.plusSeconds(1)))
                .isEqualTo(1);

        assertThat(repository.findById(processing.getId())).get()
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(Status.FAILED);
                    assertThat(r.getError()).isEqualTo("restart");
                });
        assertThat(repository.findById(done.getId())).get().extracting(CiAlertEntity::getStatus).isEqualTo(Status.DONE);
    }
}
