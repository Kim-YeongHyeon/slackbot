package com.jirabot.slack.cialert;

// status: HTTP 상태 (네트워크·파싱 오류는 0). 답글에 "권한 없음(403)"처럼 이유를 보여주는 데 쓴다.
public class GitHubActionsException extends RuntimeException {

    private final int status;

    public GitHubActionsException(int status, String message) {
        super(message);
        this.status = status;
    }

    public GitHubActionsException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }
}
