package com.jirabot.slack.service;

import com.jirabot.slack.entity.IssueEntity;

// STUDY: 버그 ↔ Notion 동기화. 두 DB 를 관리한다.
//        - 현황 DB(status): 전체 버그 + 상태(해결/미해결). 백필 및 상태 변경 시 upsert.
//        - 해결 기록 DB(resolution): 완료된 버그의 원인/해결방법(Claude 요약). 완료 시 적재.
public interface BugNotionService {

    boolean enabled();

    /**
     * 버그 상태 변경 시 호출. 현황 DB 를 upsert 하고, 완료로 전환된 경우 해결 기록 DB 도 적재한다.
     *
     * @param toDone 이번 변경에서 완료로 전환되었는지
     */
    void syncOnStatusChange(IssueEntity issue, boolean toDone);

    /**
     * Jira 의 전체 버그를 현황 DB 로 백필한다.
     *
     * @return 처리한 버그 수
     */
    int backfillStatusDb();
}
