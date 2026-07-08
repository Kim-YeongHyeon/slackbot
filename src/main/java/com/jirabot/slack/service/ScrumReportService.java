package com.jirabot.slack.service;

import java.util.concurrent.CompletableFuture;

public interface ScrumReportService {

    CompletableFuture<String> generateReport();

    CompletableFuture<String> generateMyReport(String slackUserId);

    /**
     * 내 작업 리포트. excludeDone=true 면 완료 이슈를 제외한다
     * ("완료 안된 task 알려줘" 같은 미완료-한정 질의용).
     */
    CompletableFuture<String> generateMyReport(String slackUserId, boolean excludeDone);

    CompletableFuture<String> generateMemberReport(String memberName);

    CompletableFuture<String> generateStatisticsReport();
}
