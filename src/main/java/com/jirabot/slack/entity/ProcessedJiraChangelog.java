package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

// STUDY: Jira webhook 의 changelog.id 를 보존해 동일 변경이 두 번 통보되는 경우를 차단한다.
//        Jira 가 5xx 또는 네트워크 지연 시 같은 페이로드를 재전송하는 패턴에 대비.
//
// STUDY(중요): @Id 를 직접 할당하는(=assigned, @GeneratedValue 아님) 엔티티는 Spring Data 의
//        save() 가 "이미 존재하는 엔티티"로 간주해 persist() 대신 merge() 를 호출한다. merge 는
//        PK 가 이미 있어도 예외 없이 UPSERT(UPDATE) 하므로, saveAndFlush 가 DataIntegrityViolationException
//        을 던질 거라 가정한 멱등 가드가 영영 작동하지 않는다(중복 알림 발생).
//        Persistable.isNew()=true 로 강제하면 save() 가 persist() 를 호출하고, 중복 PK INSERT 는
//        DataIntegrityViolationException 으로 터져 가드가 정상 동작한다.
@Entity
@Table(name = "processed_jira_changelog")
public class ProcessedJiraChangelog implements Persistable<String> {

    @Id
    @Column(nullable = false, length = 64)
    private String changelogId;

    @Column(nullable = false)
    private Instant processedAt;

    // STUDY: 영속 상태가 아니므로 컬럼에서 제외(@Transient). 새 인스턴스는 항상 isNew=true 로 시작하고,
    //        실제 INSERT 성공(@PostPersist) 또는 DB 로드(@PostLoad) 후에는 false 로 내려 이후 save 가 merge 되게 한다.
    @Transient
    private boolean isNew = true;

    protected ProcessedJiraChangelog() {}

    public ProcessedJiraChangelog(String changelogId) {
        this.changelogId = changelogId;
        this.processedAt = Instant.now();
    }

    @Override
    public String getId() {
        return changelogId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getChangelogId() {
        return changelogId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
