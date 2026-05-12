package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.IssueSearchEntry;
import com.jirabot.slack.client.dto.IntentResult;
import java.util.List;

public interface ClaudeApiClient {

    IssueClassification classify(String rawText);

    IssueClassification classify(String rawText, IntentResult intentHint);

    // STUDY: Sonnet 기반 의미 검색. 사용자 질문과 이슈 목록을 Sonnet에게 전달하여 관련도 높은 이슈 키를 반환받는다.
    List<String> searchIssues(String userQuery, List<IssueSearchEntry> issues);
}
