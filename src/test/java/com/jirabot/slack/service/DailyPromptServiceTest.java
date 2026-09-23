package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.config.DailyPromptProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.support.CronExpression;

class DailyPromptServiceTest {

    private SlackNotifier slack;

    @BeforeEach
    void setUp() {
        slack = mock(SlackNotifier.class);
    }

    private DailyPromptService service(Boolean enabled, String channel, String messageFile) {
        return new DailyPromptService(slack,
                new DailyPromptProperties(enabled, channel, null, null, messageFile));
    }

    @Test
    void run_postsDefaultMessageToChannel() {
        service(true, "C06702A7BPD", null).run();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(slack).postMessage(org.mockito.ArgumentMatchers.eq("C06702A7BPD"), text.capture());
        assertThat(text.getValue())
                .startsWith(":sunny: *오늘의 부탁!*")
                .contains("스레드 1개당 하나의 업무")
                .contains("```");
    }

    @Test
    void run_disabled_skips() {
        service(false, "C06702A7BPD", null).run();
        verify(slack, never()).postMessage(anyString(), anyString());
    }

    @Test
    void run_nullEnabled_treatedAsOn() {
        service(null, "C1", null).run();
        verify(slack).postMessage(org.mockito.ArgumentMatchers.eq("C1"), anyString());
    }

    @Test
    void run_blankChannel_skips() {
        // 채널 설정 누락이 엉뚱한 곳 발송으로 이어지면 안 된다
        service(true, " ", null).run();
        verify(slack, never()).postMessage(anyString(), anyString());
    }

    @Test
    void run_externalMessageFile_overridesDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("msg.txt");
        Files.writeString(f, "  바뀐 문구입니다\n", StandardCharsets.UTF_8);

        service(true, "C1", f.toString()).run();
        verify(slack).postMessage("C1", "바뀐 문구입니다");
    }

    @Test
    void run_missingMessageFile_skipsInsteadOfSendingEmpty() {
        service(true, "C1", "/nonexistent/daily.txt").run();
        verify(slack, never()).postMessage(anyString(), anyString());
    }

    @Test
    void run_emptyMessageFile_skips(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("empty.txt");
        Files.writeString(f, "  \n");
        service(true, "C1", f.toString()).run();
        verify(slack, never()).postMessage(anyString(), anyString());
    }

    @Test
    void defaultCron_firesWeekdays0930Only() {
        // 기본 cron 이 평일 09:30 에만 점화되는지 — 금요일 이후 다음 점화는 월요일이어야 한다.
        DailyPromptProperties p = new DailyPromptProperties(null, "C1", null, null, null);
        CronExpression cron = CronExpression.parse(p.cron());

        LocalDateTime fri = LocalDateTime.of(2026, 9, 25, 9, 31); // 금 09:31
        LocalDateTime next = cron.next(fri);
        assertThat(next.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(next.getHour()).isEqualTo(9);
        assertThat(next.getMinute()).isEqualTo(30);

        LocalDateTime tueEarly = LocalDateTime.of(2026, 9, 22, 8, 0);
        assertThat(cron.next(tueEarly)).isEqualTo(LocalDateTime.of(2026, 9, 22, 9, 30));
        assertThat(p.zone()).isEqualTo("Asia/Seoul");
    }
}
