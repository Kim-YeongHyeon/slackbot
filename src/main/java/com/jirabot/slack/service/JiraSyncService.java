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

    /**
     * Jira 에서 삭제된 이슈를 로컬 DB에서 정리한다.
     * 활성 스프린트+백로그 fetch 에 없는 "미완료" 로컬 이슈만 후보로 보고, Jira 에 직접 조회해
     * 404(삭제)인 것만 삭제한다. 완료 이슈는 보존(리포트/이력용), 불확실하면 보존.
     *
     * @return 삭제한 이슈 수
     */
    int pruneDeletedIssues();

    /**
     * 전체 동기화: 활성 스프린트 + 백로그 + 삭제 이슈 정리. 결과 요약 메시지 반환.
     */
    String fullSync();
}
