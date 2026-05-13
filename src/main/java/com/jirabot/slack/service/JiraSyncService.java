package com.jirabot.slack.service;

public interface JiraSyncService {

    /**
     * 활성 스프린트의 모든 이슈를 Jira에서 조회하여 로컬 DB에 동기화한다.
     * 이미 존재하는 이슈는 업데이트, 없는 이슈는 신규 생성.
     *
     * @return 동기화 결과 요약 메시지
     */
    String syncActiveSprint();

    /**
     * 스프린트에 포함되지 않은 backlog 이슈를 동기화한다.
     * 검색 범위 확장용 (통계에는 미포함).
     */
    String syncBacklog();
}
