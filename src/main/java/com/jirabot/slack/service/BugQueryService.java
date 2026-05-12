package com.jirabot.slack.service;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

public interface BugQueryService {
    CompletableFuture<String> queryResolvedBugs(LocalDate since);
}
