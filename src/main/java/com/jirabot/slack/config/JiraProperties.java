package com.jirabot.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: @ConfigurationProperties는 application.yml의 prefix 아래 값을 record에 바인딩한다.
//        record 필드명은 kebab-case yml 키와 camelCase로 자동 매핑 (relaxed binding).
@ConfigurationProperties(prefix = "jira")
public record JiraProperties(
        String baseUrl,
        String email,
        String apiToken,
        String projectKey,
        // STUDY: Jira Cloud의 Story Point 커스텀 필드 ID는 사이트마다 다르다.
        //        Jira 관리자 > 커스텀 필드에서 확인 가능. 기본값은 Jira Software 표준.
        String storyPointField,
        IssueTypes issueTypes
) {
    // STUDY: nested record로 계층적 yml 구조를 바인딩. jira.issue-types.bug = "Bug" 형태.
    public record IssueTypes(
            String bug,
            String task,
            String subtask,
            String epic
    ) {
        // STUDY: 기존 3-인자 호출부(테스트/기본값)를 깨지 않으려는 위임 생성자. Spring @ConfigurationProperties
        //        는 record 의 canonical(4-인자) 생성자로 바인딩하므로, 추가 생성자가 있어도 바인딩에 영향 없다.
        public IssueTypes(String bug, String task, String subtask) {
            this(bug, task, subtask, null);
        }

        public IssueTypes {
            if (bug == null) bug = "Bug";
            if (task == null) task = "Task";
            if (subtask == null) subtask = "Sub-task";
            if (epic == null) epic = "Epic";
        }
    }

    public JiraProperties {
        if (storyPointField == null) storyPointField = "customfield_10036";
        if (issueTypes == null) issueTypes = new IssueTypes(null, null, null);
    }
}
