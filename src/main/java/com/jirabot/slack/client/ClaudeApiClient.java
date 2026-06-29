package com.jirabot.slack.client;

import com.jirabot.slack.client.dto.BugCategoryResult;
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

    // STUDY: 버그 원인 카테고리 자동 분류. 제목+설명을 prompts/bug-category.md 체계로 분류해 {primary, secondaries} 반환.
    //        실패 시 BugCategoryResult.empty(). 봇이 버그 완료 시 Notion 현황 DB의 원인분류/세부원인을 채우는 데 사용.
    BugCategoryResult classifyBugCategory(String summary, String description);

    // STUDY: 한글 이슈 요약 → 영어 git 브랜치 슬러그(짧은 영문 구). 권장 브랜치명 생성에 사용.
    //        실패/타임아웃 시 빈 문자열 반환 → 호출부가 issueKey 만으로 브랜치명을 만든다.
    String englishBranchSlug(String summary);
}
