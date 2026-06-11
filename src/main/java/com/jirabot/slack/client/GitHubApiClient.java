package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.BranchResult;

// STUDY: GitHub 브랜치 생성을 얇은 interface 뒤에 둔다(lessons L3) — 외부 HTTP 의존을 테스트에서 목으로 대체.
public interface GitHubApiClient {

    /**
     * org/{repo} 의 기본 브랜치(default_branch)를 base 로 branchName 브랜치를 생성한다.
     * 토큰 미설정/실패 시 FAILED, 이미 존재하면 ALREADY_EXISTS 를 반환(예외 던지지 않음).
     */
    BranchResult createBranch(String repo, String branchName);
}
