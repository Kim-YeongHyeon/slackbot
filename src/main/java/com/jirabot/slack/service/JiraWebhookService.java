package com.jirabot.slack.service;

public interface JiraWebhookService {

    /**
     * Jira webhook 페이로드(JSON 본문) 를 처리한다.
     * 봇이 만들지 않은 이슈, Slack 스레드 정보가 없는 이슈, 이미 처리한 changelog 는 모두 멱등으로 무시.
     */
    void process(String jsonBody);
}
