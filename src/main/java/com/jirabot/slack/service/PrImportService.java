package com.jirabot.slack.service;

public interface PrImportService {

    /** PR import 결과. success=false 면 message 에 사유. assignee=보고자/담당자로 지정된 이름. */
    record Result(boolean success, String issueKey, String issueUrl, int storyPoint,
                  double businessDays, String finalStatus, String assignee, String message) {
        static Result fail(String message) {
            return new Result(false, null, null, 0, 0, null, null, message);
        }
    }

    /**
     * PR URL 을 받아 Jira 티켓을 만들고, 내용 분석으로 분류/제목/요약을 채운 뒤 현재 스프린트로 옮긴다.
     * PR 상태에 따라 전환 목표가 다르다:
     *   - merged          → 해야 할 일→진행 중→검토 중→완료
     *   - open & ready    → 해야 할 일→진행 중→검토 중
     *   - open & draft    → 해야 할 일→진행 중
     * Story Point 는 생성→(merge 또는 현재) 영업일로 산정한다.
     *
     * @param prUrl       https://github.com/{owner}/{repo}/pull/{number}
     * @param slackUserId 명령 실행자 Slack ID (보고자 매핑 폴백용, 없으면 null → 토큰 소유자)
     */
    Result importPr(String prUrl, String slackUserId);
}
