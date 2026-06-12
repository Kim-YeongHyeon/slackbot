package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.BranchResult;
import com.jirabot.slack.client.dto.PullRequestInfo;
import java.util.List;

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
}
