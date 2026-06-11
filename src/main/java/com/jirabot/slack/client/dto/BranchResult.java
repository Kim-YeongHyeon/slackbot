package com.jirabot.slack.client.dto;

// STUDY: GitHub 브랜치 생성 결과. 상태별로 Slack 회신 문구를 분기하기 위한 경량 record.
public record BranchResult(Status status, String branchName, String htmlUrl, String message) {

    public enum Status { CREATED, ALREADY_EXISTS, FAILED }

    public static BranchResult created(String branchName, String htmlUrl) {
        return new BranchResult(Status.CREATED, branchName, htmlUrl, null);
    }

    public static BranchResult alreadyExists(String branchName, String htmlUrl) {
        return new BranchResult(Status.ALREADY_EXISTS, branchName, htmlUrl, null);
    }

    public static BranchResult failed(String branchName, String message) {
        return new BranchResult(Status.FAILED, branchName, null, message);
    }
}
