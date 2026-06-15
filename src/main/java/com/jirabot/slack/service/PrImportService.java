package com.jirabot.slack.service;

public interface PrImportService {

    /** PR import 결과. success=false 면 message 에 사유. */
    record Result(boolean success, String issueKey, String issueUrl, int storyPoint,
                  double businessDays, String finalStatus, String message) {
        static Result fail(String message) {
            return new Result(false, null, null, 0, 0, null, message);
        }
    }

    /**
     * 완료(merge)된 PR URL 을 받아 Jira 티켓을 만들고, 내용 분석으로 분류/제목/요약을 채운 뒤,
     * PR 생성→merge 영업일로 Story Point 를 산정하고, 현재 스프린트로 옮겨
     * 해야 할 일→진행 중→검토 중→완료까지 한번에 전환한다.
     *
     * @param prUrl       https://github.com/{owner}/{repo}/pull/{number}
     * @param slackUserId 명령 실행자 Slack ID (보고자 매핑용, 없으면 null → 토큰 소유자)
     */
    Result importMergedPr(String prUrl, String slackUserId);
}
