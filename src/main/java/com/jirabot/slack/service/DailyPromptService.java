package com.jirabot.slack.service;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.DailyPromptProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// STUDY: 평일 아침 "오늘의 부탁" 채널 메시지 (v0.0.78). ReminderService 와 같은 @Scheduled 패턴.
//        문구는 발송 시점마다 읽는다 — messageFile 을 지정했다면 파일만 고쳐도 다음 발송부터 반영된다.
@Component
public class DailyPromptService {

    private static final Logger log = LoggerFactory.getLogger(DailyPromptService.class);
    static final String DEFAULT_MESSAGE_RESOURCE = "messages/daily-prompt.txt";

    private final SlackNotifier slackNotifier;
    private final DailyPromptProperties props;

    public DailyPromptService(SlackNotifier slackNotifier, DailyPromptProperties props) {
        this.slackNotifier = slackNotifier;
        this.props = props;
    }

    // STUDY: cron 표현식도 프로퍼티 플레이스홀더로 주입 — "초 분 시 일 월 요일", MON-FRI = 평일.
    @Scheduled(cron = "${daily-prompt.cron:0 30 9 * * MON-FRI}", zone = "${daily-prompt.zone:Asia/Seoul}")
    public void run() {
        if (!props.effectivelyEnabled()) {
            log.info("Daily prompt skipped: daily-prompt.enabled=false");
            return;
        }
        if (props.channel() == null || props.channel().isBlank()) {
            log.warn("Daily prompt skipped: daily-prompt.channel is empty");
            return;
        }
        String message = loadMessage();
        if (message == null || message.isBlank()) {
            log.warn("Daily prompt skipped: message is empty");
            return;
        }
        slackNotifier.postMessage(props.channel(), message);
        log.info("Daily prompt sent channel={}", props.channel());
    }

    // 외부 파일 지정 시 그것을, 아니면 classpath 기본 문구. 읽기 실패는 null (발송 생략 + 경고 로그).
    String loadMessage() {
        try {
            if (props.messageFile() != null && !props.messageFile().isBlank()) {
                return Files.readString(Path.of(props.messageFile()), StandardCharsets.UTF_8).strip();
            }
            try (InputStream in = new ClassPathResource(DEFAULT_MESSAGE_RESOURCE).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            log.warn("Daily prompt message load failed: {}", e.toString());
            return null;
        }
    }
}
