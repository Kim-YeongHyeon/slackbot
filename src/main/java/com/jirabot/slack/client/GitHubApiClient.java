package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.BranchResult;
import com.jirabot.slack.client.dto.PullRequestDetail;
import com.jirabot.slack.client.dto.PullRequestInfo;
import java.util.List;
import java.util.Optional;

// STUDY: GitHub 브랜치 생성을 얇은 interface 뒤에 둔다(lessons L3) — 외부 HTTP 의존을 테스트에서 목으로 대체.
public interface GitHubApiClient {

    /**
     * org/{repo} 의 기본 브랜치(default_branch)를 base 로 branchName 브랜치를 생성한다.
     * 토큰 미설정/실패 시 FAILED, 이미 존재하면 ALREADY_EXISTS 를 반환(예외 던지지 않음).
     */
    BranchResult createBranch(String repo, String branchName);

    /**
     * org/{repo} 의 열린 PR 목록 (대시보드 PR 현황 탭).
     *
     * @throws GitHubAccessException 4xx — 토큰에 Pull requests: Read 권한이 없거나 repo 접근 불가
     *         (5xx/네트워크 오류는 빈 목록 반환 — 부분 데이터라도 표시)
     */
    List<PullRequestInfo> listOpenPullRequests(String repo);

    /**
     * {owner}/{repo} 의 단건 PR 상세를 조회한다 (merge 완료 PR → Jira 티켓 import 용).
     * 토큰 미설정/404/권한오류/네트워크 오류 시 empty.
     */
    Optional<PullRequestDetail> getPullRequest(String owner, String repo, int number);

    /**
     * GitHub 사용자(login)의 프로필 표시 이름(name)을 조회한다. name 이 없으면 login 자체를 반환.
     * PR 작성자 → Jira 사용자 매핑(Jira user search)용. 토큰 미설정/404/오류 시 empty.
     */
    Optional<String> getUserDisplayName(String login);

    /**
     * 이슈 키(예: ES2-123)를 참조하는 PR들의 html_url 목록을 GitHub 검색으로 찾는다.
     * 버그 완료 시 Notion '버그 현황'에 해결 PR 링크를 채우는 데 사용. 토큰 미설정/오류 시 빈 목록.
     */
    List<String> searchPullRequestUrls(String issueKey);
}
