package com.jirabot.slack.client;

import java.util.List;

public interface SlackNotifier {

    /**
     * Slack 채널의 특정 스레드에 메시지를 남긴다.
     *
     * @param channel  채널 ID
     * @param threadTs 스레드 부모 메시지의 ts (스레드 댓글로 달림)
     * @param text     메시지 본문
     */
    void postThreadReply(String channel, String threadTs, String text);

    void postMessage(String channel, String text);

    /**
     * Slack 사용자에게 DM 을 발송한다.
     * 내부적으로 conversations.open 으로 IM 채널 ID(D...) 를 받은 뒤 그 채널 ID 로 chat.postMessage 를 보낸다.
     * 봇 스코프: chat:write + im:write.
     *
     * @param userId Slack 사용자 ID (예: U03...)
     * @param text   메시지 본문
     */
    void sendDirectMessage(String userId, String text);

    /**
     * Slack 유저 ID로 실명(real_name)을 조회한다.
     *
     * @param userId Slack 유저 ID (예: U03L1TJ0EBB)
     * @return 실명, 실패 시 null
     */
    String getUserRealName(String userId);

    /**
     * Read all messages in a Slack thread.
     *
     * @param channel channel ID
     * @param threadTs parent message timestamp
     * @return list of message texts (oldest first), empty list on failure
     */
    List<String> getThreadMessages(String channel, String threadTs);

    /**
     * Block Kit JSON 포함 스레드 메시지 전송.
     *
     * @param channel   채널 ID
     * @param threadTs  스레드 부모 메시지의 ts
     * @param text      Block Kit 미지원 클라이언트용 fallback 텍스트
     * @param blocksJson Block Kit JSON 배열 문자열
     */
    void postBlockMessage(String channel, String threadTs, String text, String blocksJson);

    /**
     * 기존 메시지를 업데이트한다 (chat.update API).
     *
     * @param channel   채널 ID
     * @param messageTs 업데이트할 메시지의 ts
     * @param text      fallback 텍스트
     * @param blocksJson Block Kit JSON 배열 문자열 (null이면 blocks 제거)
     */
    void updateMessage(String channel, String messageTs, String text, String blocksJson);
}
