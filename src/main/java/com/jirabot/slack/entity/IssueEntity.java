package com.jirabot.slack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// STUDY: @Entity는 JPA가 관리하는 영속 객체. @Table로 테이블명을 명시하면 클래스명과 분리 가능.
// STUDY: ddl-auto=update 상태에서는 이 Entity 추가 시 테이블이 자동 생��된다.
@Entity
@Table(name = "issues")
public class IssueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // STUDY: unique=true → DB에 유니크 인덱스 생성. Jira 이슈 키는 프로젝트 내 고유.
    @Column(nullable = false, unique = true)
    private String issueKey;

    @Column(nullable = false)
    private String summary;

    private String issueType;

    private String status;

    private String statusCategory;

    private String assignee;

    private Double storyPoint;

    private String reporter;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Instant jiraCreated;

    private Instant jiraUpdated;

    // STUDY: 이 이슈가 생성된 Slack 스레드 정보. 스레드에서 "@지라 완료" 시 이슈를 매핑하는 데 사용.
    private String slackChannel;
    private String slackThreadTs;

    // STUDY: 상태가 "완료"로 변경된 시점. jiraUpdated와 별개로 관리해야
    //        완료 후 설명 수정 등으로 jiraUpdated가 갱신되어도 완료 시점이 유지된다.
    private Instant completedAt;

    // STUDY: 이슈가 속한 Jira 스프린트 정보. 동기화 시 활성 스프린트의 ID/이름을 함께 저장한다.
    //        통계 기능에서 현재 스프린트 이슈만 필터링하는 데 사용.
    private Integer sprintId;
    private String sprintName;

    // STUDY: 동기화 시점을 기록해서 마지막 동기화 이후 변경분만 가져올 수 있다.
    private Instant syncedAt;

    protected IssueEntity() {}

    public IssueEntity(String issueKey, String summary, String issueType, String status,
                       String statusCategory, String assignee, Double storyPoint,
                       String reporter, String description, Instant jiraCreated, Instant jiraUpdated) {
        this.issueKey = issueKey;
        this.summary = summary;
        this.issueType = issueType;
        this.status = status;
        this.statusCategory = statusCategory;
        this.assignee = assignee;
        this.storyPoint = storyPoint;
        this.reporter = reporter;
        this.description = description;
        this.jiraCreated = jiraCreated;
        this.jiraUpdated = jiraUpdated;
        this.syncedAt = Instant.now();
        this.completedAt = StatusCategory.DONE.equals(statusCategory) ? Instant.now() : null;
    }

    public void updateFrom(String summary, String issueType, String status, String statusCategory,
                           String assignee, Double storyPoint, Instant jiraUpdated) {
        boolean wasNotComplete = !StatusCategory.DONE.equals(this.statusCategory);
        this.summary = summary;
        this.issueType = issueType;
        this.status = status;
        this.statusCategory = statusCategory;
        this.assignee = assignee;
        this.storyPoint = storyPoint;
        this.jiraUpdated = jiraUpdated;
        this.syncedAt = Instant.now();
        // 완료로 전환된 시점만 기록. 이미 완료였으면 유지.
        if (StatusCategory.DONE.equals(statusCategory) && wasNotComplete) {
            this.completedAt = Instant.now();
        } else if (!StatusCategory.DONE.equals(statusCategory)) {
            this.completedAt = null;
        }
    }

    /**
     * 상태만 변경할 때 사용하는 간편 메서드.
     * STUDY: Jira의 status(예: "검토 중")와 statusCategory(예: "진행 중")는 다른 개념이다.
     *        status는 워크플로 상태명, statusCategory는 3가지(해야 할 일/진행 중/완료) 중 하나.
     */
    public void updateStatus(String status, String statusCategory) {
        boolean wasNotComplete = !StatusCategory.DONE.equals(this.statusCategory);
        this.status = status;
        this.statusCategory = statusCategory;
        this.jiraUpdated = Instant.now();
        this.syncedAt = Instant.now();
        if (StatusCategory.DONE.equals(statusCategory) && wasNotComplete) {
            this.completedAt = Instant.now();
        } else if (!StatusCategory.DONE.equals(statusCategory)) {
            this.completedAt = null;
        }
    }

    public Long getId() { return id; }
    public String getIssueKey() { return issueKey; }
    public String getSummary() { return summary; }
    public String getIssueType() { return issueType; }
    public String getStatus() { return status; }
    public String getStatusCategory() { return statusCategory; }
    public String getAssignee() { return assignee; }
    public Double getStoryPoint() { return storyPoint; }
    public String getReporter() { return reporter; }
    public String getDescription() { return description; }
    public Instant getJiraCreated() { return jiraCreated; }
    public Instant getJiraUpdated() { return jiraUpdated; }
    public String getSlackChannel() { return slackChannel; }
    public String getSlackThreadTs() { return slackThreadTs; }
    public void setSlackThread(String channel, String threadTs) {
        this.slackChannel = channel;
        this.slackThreadTs = threadTs;
    }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Integer getSprintId() { return sprintId; }
    public String getSprintName() { return sprintName; }
    public void setSprint(int sprintId, String sprintName) {
        this.sprintId = sprintId;
        this.sprintName = sprintName;
    }
    public Instant getSyncedAt() { return syncedAt; }
}
