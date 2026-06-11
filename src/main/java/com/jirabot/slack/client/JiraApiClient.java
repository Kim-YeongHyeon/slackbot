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
     * Jira 에 해당 이슈가 존재하는지. 404 면 false(삭제됨), 200 이면 true.
     * 불확실(네트워크/5xx)하면 보존을 위해 true 를 반환한다(삭제 prune 의 오삭제 방지).
     */
    boolean issueExists(String issueKey);

    /**
     * 단건 이슈를 라이브 조회한다 (이슈 키 조회 카드의 로컬 DB 미보유 폴백).
     * 404(삭제/권한 없음) 또는 실패 시 empty.
     */
    Optional<SprintIssue> getIssue(String issueKey);

    /**
     * 이슈의 담당자를 변경한다.
     *
     * @param accountId 새 담당자 Jira accountId (null 이면 담당자 해제)
     * @return 성공 여부 (204)
     */
    boolean assignIssue(String issueKey, String accountId);

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

    /**
     * JQL 로 이슈를 조회한다(페이지네이션 포함). 버그 백필 등 범용 검색용.
     */
    List<SprintIssue> searchByJql(String jql);

    /**
     * 이슈의 댓글 본문 목록을 조회한다(오래된 순). 버그 해결 요약 입력용.
     */
    List<String> getComments(String issueKey);
}
