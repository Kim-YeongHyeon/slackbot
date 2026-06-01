package com.jirabot.slack.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jirabot.slack.entity.ProcessedJiraChangelog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

// STUDY: 멱등성 회귀 테스트. ProcessedJiraChangelog 가 Persistable.isNew()=true 를 강제하므로
//        save() 가 persist() 를 호출하고, 동일 changelog.id 재전송은 PK 충돌로 DataIntegrityViolationException
//        이 발생해야 한다. (Persistable 미적용 시 save() 가 merge UPSERT 라 예외가 안 나고 중복 알림 발생 → 이 테스트가 FAIL)
//        프로젝트 관례대로 @SpringBootTest + @ActiveProfiles("test") 의 H2(MODE=PostgreSQL) 컨텍스트 사용.
//        클래스 레벨 @Transactional 은 두지 않는다 — 각 repository 호출이 독립 트랜잭션이어야
//        예외 후 existsById 조회가 깨진 세션에 걸리지 않는다.
@SpringBootTest
@ActiveProfiles("test")
class ProcessedJiraChangelogRepositoryTest {

    @Autowired
    private ProcessedJiraChangelogRepository repository;

    @Test
    void duplicateChangelogId_secondSaveThrowsConstraintViolation() {
        repository.saveAndFlush(new ProcessedJiraChangelog("changelog-dup-1"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(new ProcessedJiraChangelog("changelog-dup-1")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 최초 1건은 보존(merge UPSERT 였다면 갱신만 되고 예외도 안 났을 것).
        assertThat(repository.existsById("changelog-dup-1")).isTrue();
    }

    @Test
    void distinctChangelogIds_bothPersist() {
        repository.saveAndFlush(new ProcessedJiraChangelog("changelog-distinct-a"));
        repository.saveAndFlush(new ProcessedJiraChangelog("changelog-distinct-b"));

        assertThat(repository.existsById("changelog-distinct-a")).isTrue();
        assertThat(repository.existsById("changelog-distinct-b")).isTrue();
    }
}
