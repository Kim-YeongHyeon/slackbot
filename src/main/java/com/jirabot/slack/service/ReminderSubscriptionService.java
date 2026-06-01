package com.jirabot.slack.service;

public interface ReminderSubscriptionService {

    /**
     * 호출자의 리마인더 수신을 켠다. 매핑이 없으면 안내 메시지 반환.
     * @return Slack 스레드 회신용 메시지
     */
    String enable(String slackUserId);

    /** 끈다. 매핑이 없어도 멱등 처리. */
    String disable(String slackUserId);

    /** 현재 상태와 다음 발송 일정 응답. */
    String status(String slackUserId);
}
