package com.jirabot.slack.client.dto;

// conversations.history 로 읽은 메시지 한 건 (CI 알림 재처리용, v0.0.91).
public record SlackHistoryMessage(String ts, String text, String user, String botId, String subtype, String threadTs) {}
