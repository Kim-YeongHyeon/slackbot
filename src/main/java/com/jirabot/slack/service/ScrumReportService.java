package com.jirabot.slack.service;

import java.util.concurrent.CompletableFuture;

public interface ScrumReportService {

    CompletableFuture<String> generateReport();

    CompletableFuture<String> generateMyReport(String slackUserId);

    CompletableFuture<String> generateMemberReport(String memberName);

    CompletableFuture<String> generateStatisticsReport();
}
