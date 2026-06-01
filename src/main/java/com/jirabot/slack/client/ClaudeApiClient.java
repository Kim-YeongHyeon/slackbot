package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.BugResolutionSummary;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.IssueSearchEntry;
import com.jirabot.slack.client.dto.IntentResult;
import java.util.List;

public interface ClaudeApiClient {

    IssueClassification classify(String rawText);

    IssueClassification classify(String rawText, IntentResult intentHint);

    // STUDY: Sonnet 기반 의미 검색. 사용자 질문과 이슈 목록을 Sonnet에게 전달하여 관련도 높은 이슈 키를 반환받는다.
    List<String> searchIssues(String userQuery, List<IssueSearchEntry> issues);

    // STUDY: 버그 완료 시 원인/해결방법 요약. Jira 설명+댓글+Slack 스레드를 입력으로 받아 {cause, fix} JSON 반환.
    BugResolutionSummary summarizeBugResolution(String issueKey, String description,
                                                List<String> comments, List<String> threadMessages);
}
