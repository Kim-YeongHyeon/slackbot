package com.jirabot.slack.entity;

// STUDY: 매직 스트링을 상수 클래스로 추출하여 오타 방지 및 일관성 보장.
//        Jira의 statusCategory 필드 값은 프로젝트 설정에 따라 다를 수 있으나,
//        한국어 Jira 기준 "완료", "진행 중", "해야 할 일" 3가지 카테고리만 존재.
public final class StatusCategory {

    public static final String DONE = "완료";
    public static final String IN_PROGRESS = "진행 중";
    public static final String TODO = "해야 할 일";

    private StatusCategory() {}
}
