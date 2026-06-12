package com.jirabot.slack.client;

// STUDY: GitHub 4xx(권한 부족/저장소 없음) 전용 — fine-grained 토큰은 권한이 없으면 403 이 아니라
//        404 를 반환하므로 "repo 가 없다" 와 구분이 안 된다. 대시보드는 이 예외를 잡아
//        해당 repo 를 "접근 불가" 로 표시한다 (5xx/네트워크 오류와 구분).
public class GitHubAccessException extends RuntimeException {
    public GitHubAccessException(String message) {
        super(message);
    }
}
