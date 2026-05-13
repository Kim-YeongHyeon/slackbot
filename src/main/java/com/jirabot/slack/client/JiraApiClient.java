package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.client.dto.SprintIssue;
import java.util.List;
import java.util.Optional;

public interface JiraApiClient {

    JiraCreateResponse createIssue(IssueClassification classification, String reporterName,
                                   String jiraAccountId);

    /**
     * Jira displayName으로 유저를 검색하여 accountId를 반환한다.
     */
    String findAccountId(String displayName);

    Optional<SprintInfo> getActiveSprint();

    List<SprintIssue> getSprintIssues(int sprintId);

    /**
     * Kanban backlog 이슈를 조회한다 (스프린트에 포함되지 않은 이슈).
     * 검색 범위 확장용.
     */
    List<SprintIssue> getBacklogIssues();

    /**
     * Jira 이슈의 상태를 전환한다.
     *
     * @param issueKey 이슈 키 (예: SLAC-7)
     * @param targetStatusName 목표 상태명 (예: "완료")
     * @return 성공 여부
     */
    boolean transitionIssue(String issueKey, String targetStatusName);

    /**
     * Create a sub-task under a parent issue.
     *
     * @param jiraAccountId 보고자/담당자 Jira accountId (null이면 API 토큰 소유자가 기본값)
     */
    String createSubTask(String parentKey, String summary, int storyPoint, String jiraAccountId);

    /**
     * 이슈를 활성 스프린트로 이동한다.
     *
     * @return 성공 여부
     */
    boolean moveToActiveSprint(String issueKey);

    /**
     * Add a comment to an existing issue.
     */
    void addComment(String issueKey, String commentText);

    /**
     * Append text to an existing issue's description.
     */
    void appendDescription(String issueKey, String additionalText);
}
