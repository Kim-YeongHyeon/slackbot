package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReminderServiceTest {

    private static final int SPRINT_ID = 933;
    // 격주 anchor(2026-06-22, 월). 06-22=발송, 06-29=미발송, 07-06=발송. 06-09(화)=평일 일일 발송일.
    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 22);
    private static final LocalDate DAILY_DAY = LocalDate.of(2026, 6, 9);   // 화요일, 격주일 아님
    private static final LocalDate BIWEEKLY_ON = LocalDate.of(2026, 6, 22); // 짝수 주차 월
    private static final LocalDate BIWEEKLY_OFF = LocalDate.of(2026, 6, 29); // 홀수 주차 월

    private UserMappingRepository userMappingRepository;
    private IssueRepository issueRepository;
    private SlackNotifier slackNotifier;
    private JiraApiClient jira;
    private ReminderService service;

    @BeforeEach
    void setUp() {
        userMappingRepository = mock(UserMappingRepository.class);
        issueRepository = mock(IssueRepository.class);
        slackNotifier = mock(SlackNotifier.class);
        jira = mock(JiraApiClient.class);
    }

    private void rebuild(boolean enabled) {
        ReminderProperties reminderProps = new ReminderProperties(
                enabled, "0 0 9 * * MON-FRI", "Asia/Seoul", "0 30 9 * * MON", ANCHOR.toString());
        JiraProperties jiraProps = new JiraProperties(
                "https://cryptolab.atlassian.net", "u@x", "t", "ES2",
                "customfield_10036", new JiraProperties.IssueTypes("Bug", "Task", "Sub-task"));
        service = new ReminderService(
                userMappingRepository, issueRepository, slackNotifier, jira, reminderProps, jiraProps);
    }

    private UserMappingEntity subscriber(String slackUserId, String jiraDisplayName) {
        UserMappingEntity entity = new UserMappingEntity(slackUserId, slackUserId, jiraDisplayName);
        entity.setReminderEnabled(true);
        return entity;
    }

    private IssueEntity issue(String key, String summary, String assignee, String reporter) {
        return new IssueEntity(key, summary, "작업", "진행 중", "진행 중",
                assignee, 2.0, reporter, "본문", Instant.now(), Instant.now());
    }

    private void activeSprint() {
        when(jira.getActiveSprint())
                .thenReturn(Optional.of(new SprintInfo(SPRINT_ID, "Sprint 3", "active", null, null)));
    }

    // ---- isBiweeklyFullToday parity ----

    @Test
    void biweeklyParity_isTrueOnAnchorAndEverySecondMonday() {
        rebuild(true);
        assertThat(service.isBiweeklyFullToday(LocalDate.of(2026, 6, 22))).isTrue();   // anchor
        assertThat(service.isBiweeklyFullToday(LocalDate.of(2026, 6, 29))).isFalse();  // off-week
        assertThat(service.isBiweeklyFullToday(LocalDate.of(2026, 7, 6))).isTrue();    // +2 weeks
        assertThat(service.isBiweeklyFullToday(LocalDate.of(2026, 7, 20))).isTrue();   // +4 weeks
    }

    @Test
    void biweeklyParity_falseBeforeAnchorAndOnNonMonday() {
        rebuild(true);
        assertThat(service.isBiweeklyFullToday(LocalDate.of(2026, 6, 8))).isFalse();   // 이전 월요일
        assertThat(service.isBiweeklyFullToday(LocalDate.of(2026, 6, 23))).isFalse();  // 화요일
    }

    // ---- daily (sprint scope) ----

    @Test
    void daily_enabledFalse_doesNothing() {
        rebuild(false);
        service.runDaily(DAILY_DAY);
        verify(userMappingRepository, never()).findByReminderEnabledTrue();
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void daily_skippedOnBiweeklyFullDay() {
        rebuild(true);
        service.runDaily(BIWEEKLY_ON);
        // 격주 전체 발송일엔 일일 스프린트 리마인더 생략 — 스프린트 조회조차 안 한다.
        verify(jira, never()).getActiveSprint();
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void daily_noActiveSprint_skips() {
        rebuild(true);
        when(jira.getActiveSprint()).thenReturn(Optional.empty());
        service.runDaily(DAILY_DAY);
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void daily_sendsSprintIssuesToAssignee() {
        rebuild(true);
        activeSprint();
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "Alice")));
        when(issueRepository.findByStatusCategoryNotAndSprintId("완료", SPRINT_ID))
                .thenReturn(List.of(issue("ES2-100", "로그인 에러", "Alice", null)));

        service.runDaily(DAILY_DAY);

        verify(slackNotifier).sendDirectMessage(eq("U1"),
                argThat(text -> text.contains("ES2-100")
                        && text.contains("로그인 에러")
                        && text.contains("현재 스프린트")
                        && text.contains("1건")));
    }

    @Test
    void daily_fallsBackToReporterWhenNoAssignee() {
        rebuild(true);
        activeSprint();
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "Alice")));
        // 담당자 없음 + 보고자 Alice → Alice 에게 귀속.
        when(issueRepository.findByStatusCategoryNotAndSprintId("완료", SPRINT_ID))
                .thenReturn(List.of(issue("ES2-200", "미배정 이슈", null, "Alice")));

        service.runDaily(DAILY_DAY);

        verify(slackNotifier).sendDirectMessage(eq("U1"),
                argThat(text -> text.contains("ES2-200")));
    }

    @Test
    void daily_subscriberWithoutOwnedIssues_skipsDm() {
        rebuild(true);
        activeSprint();
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "Alice")));
        // Bob 의 이슈만 → Alice 에겐 발송 없음.
        when(issueRepository.findByStatusCategoryNotAndSprintId("완료", SPRINT_ID))
                .thenReturn(List.of(issue("ES2-1", "이슈", "Bob", null)));

        service.runDaily(DAILY_DAY);

        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    // ---- biweekly (all open) ----

    @Test
    void biweekly_offWeek_doesNothing() {
        rebuild(true);
        service.runBiweekly(BIWEEKLY_OFF);
        verify(issueRepository, never()).findByStatusCategoryNot(anyString());
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void biweekly_onWeek_sendsAllOpenIssues() {
        rebuild(true);
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "Alice")));
        when(issueRepository.findByStatusCategoryNot("완료"))
                .thenReturn(List.of(
                        issue("ES2-1", "스프린트 이슈", "Alice", null),
                        issue("ES2-2", "백로그 이슈", null, "Alice")));

        service.runBiweekly(BIWEEKLY_ON);

        // 전체 범위 라벨 + 두 건 모두 포함, 활성 스프린트는 조회하지 않음.
        verify(jira, never()).getActiveSprint();
        verify(slackNotifier).sendDirectMessage(eq("U1"),
                argThat(text -> text.contains("전체")
                        && text.contains("ES2-1")
                        && text.contains("ES2-2")
                        && text.contains("2건")));
    }

    @Test
    void biweekly_oneFailedUser_doesNotBlockOthers() {
        rebuild(true);
        when(userMappingRepository.findByReminderEnabledTrue())
                .thenReturn(List.of(subscriber("U1", "Alice"), subscriber("U2", "Bob")));
        when(issueRepository.findByStatusCategoryNot("완료"))
                .thenReturn(List.of(
                        issue("ES2-1", "이슈 A", "Alice", null),
                        issue("ES2-2", "이슈 B", "Bob", null)));
        doThrow(new RuntimeException("slack down"))
                .when(slackNotifier).sendDirectMessage(eq("U1"), anyString());

        service.runBiweekly(BIWEEKLY_ON);

        verify(slackNotifier, times(1)).sendDirectMessage(eq("U1"), anyString());
        verify(slackNotifier, times(1)).sendDirectMessage(eq("U2"), anyString());
    }

    // ---- message format ----

    @Test
    void buildMessage_includesScopeLabelLinkAndStatus() {
        rebuild(true);
        String message = service.buildMessage(List.of(
                issue("ES2-1", "이슈 A", "Alice", null),
                issue("ES2-2", "이슈 B", "Alice", null)), "현재 스프린트");

        assertThat(message)
                .contains(":sunny:")
                .contains("현재 스프린트")
                .contains("2건")
                .contains("<https://cryptolab.atlassian.net/browse/ES2-1|ES2-1>")
                .contains("이슈 A")
                .contains("<https://cryptolab.atlassian.net/browse/ES2-2|ES2-2>");
    }
}
