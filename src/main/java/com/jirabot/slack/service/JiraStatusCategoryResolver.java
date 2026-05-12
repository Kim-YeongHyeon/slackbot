package com.jirabot.slack.service;

import java.util.Map;
import org.springframework.stereotype.Component;

// STUDY: status name → status category 매핑 헬퍼.
//        webhook payload 의 changelog.items[field=status] 만으로는 카테고리 정보가 없어,
//        이름 기반으로 카테고리를 추론한다. DB 의 옛 statusCategory 값에 의존하지 않게 하기 위한 분리.
//        매핑 누락 status 는 "indeterminate" 로 보수적 기본값을 둔다 (대부분 진행 중에 속함).
@Component
public class JiraStatusCategoryResolver {

    static final String NEW = "new";                       // 해야 할 일 / 백로그
    static final String IN_PROGRESS = "indeterminate";     // 진행 중 / 검토 중
    static final String DONE = "done";                     // 완료 / 종료 / 해결

    // STUDY: 한국어 / 영어 표기 모두 매핑. lowercase 비교로 대소문자 차이 흡수.
    private static final Map<String, String> CATEGORY_BY_NAME = Map.ofEntries(
            Map.entry("해야 할 일", NEW),
            Map.entry("to do", NEW),
            Map.entry("open", NEW),
            Map.entry("backlog", NEW),
            Map.entry("선택을 위한 항목", NEW),

            Map.entry("진행 중", IN_PROGRESS),
            Map.entry("in progress", IN_PROGRESS),
            Map.entry("in review", IN_PROGRESS),
            Map.entry("검토 중", IN_PROGRESS),
            Map.entry("리뷰", IN_PROGRESS),

            Map.entry("완료", DONE),
            Map.entry("종료", DONE),
            Map.entry("done", DONE),
            Map.entry("closed", DONE),
            Map.entry("resolved", DONE)
    );

    public String categoryOf(String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return IN_PROGRESS;
        }
        return CATEGORY_BY_NAME.getOrDefault(statusName.toLowerCase().strip(), IN_PROGRESS);
    }

    public boolean isDone(String statusName) {
        return DONE.equals(categoryOf(statusName));
    }
}
