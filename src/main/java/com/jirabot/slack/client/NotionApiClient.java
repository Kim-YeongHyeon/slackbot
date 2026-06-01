package com.jirabot.slack.client;

import java.util.Map;
import java.util.Optional;

// STUDY: Notion API 저수준 클라이언트. row(=database page) 의 조회/생성/수정만 제공하고,
//        도메인 매핑(버그 → 컬럼)은 상위 서비스가 담당한다.
public interface NotionApiClient {

    /** DB 에서 issueKey 를 제목에 포함하는 row 의 page id 를 찾는다(upsert 용). 없으면 empty. */
    Optional<String> findPageId(String databaseId, String issueKey);

    /** DB 에 row(page) 를 생성한다. properties 는 Notion property-value 맵(컬럼명 → 값). */
    void createRow(String databaseId, Map<String, Object> properties);

    /** 기존 row(page) 의 properties 를 갱신한다. */
    void updateRow(String pageId, Map<String, Object> properties);
}
