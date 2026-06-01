package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReminderSubscriptionServiceImplTest {

    private UserMappingRepository repository;
    private ReminderSubscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(UserMappingRepository.class);
        ReminderProperties props = new ReminderProperties(true, "0 0 9 * * MON-FRI", "Asia/Seoul");
        service = new ReminderSubscriptionServiceImpl(repository, props);
    }

    @Test
    void enable_unmappedUser_returnsGuide_andSavesNothing() {
        when(repository.findBySlackUserId("U-NEW")).thenReturn(Optional.empty());

        String reply = service.enable("U-NEW");

        assertThat(reply).contains("`@봇더지라 등록");
        verify(repository, never()).save(any());
    }

    @Test
    void enable_mappedUser_setsReminderEnabledTrue() {
        UserMappingEntity mapping = new UserMappingEntity("U1", "alice", "Alice");
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(mapping));

        String reply = service.enable("U1");

        assertThat(mapping.isReminderEnabled()).isTrue();
        assertThat(reply).contains("켜졌습니다");
        verify(repository).save(mapping);
    }

    @Test
    void enable_alreadyOn_isIdempotent_noExtraSave() {
        UserMappingEntity mapping = new UserMappingEntity("U1", "alice", "Alice");
        mapping.setReminderEnabled(true);
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(mapping));

        // 두 번 호출해도 추가 save 가 일어나지 않아야 한다.
        String first = service.enable("U1");
        String second = service.enable("U1");

        assertThat(mapping.isReminderEnabled()).isTrue();
        assertThat(first).contains("이미 켜져");
        assertThat(second).contains("이미 켜져");
        verify(repository, never()).save(any());
    }

    @Test
    void disable_alreadyOff_isIdempotent_noExtraSave() {
        UserMappingEntity mapping = new UserMappingEntity("U1", "alice", "Alice");
        // 기본 reminderEnabled=false
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(mapping));

        service.disable("U1");
        service.disable("U1");

        assertThat(mapping.isReminderEnabled()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void disable_unmappedUser_isIdempotent_andReturnsOffMessage() {
        when(repository.findBySlackUserId("U-NEW")).thenReturn(Optional.empty());

        String reply = service.disable("U-NEW");

        assertThat(reply).contains("꺼져");
        verify(repository, never()).save(any());
    }

    @Test
    void disable_mappedUser_setsReminderEnabledFalse() {
        UserMappingEntity mapping = new UserMappingEntity("U1", "alice", "Alice");
        mapping.setReminderEnabled(true);
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(mapping));

        service.disable("U1");

        assertThat(mapping.isReminderEnabled()).isFalse();
        verify(repository).save(mapping);
    }

    @Test
    void status_on_reportsScheduleSummary() {
        UserMappingEntity mapping = new UserMappingEntity("U1", "alice", "Alice");
        mapping.setReminderEnabled(true);
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(mapping));

        String reply = service.status("U1");

        assertThat(reply).contains("ON").contains("0 0 9 * * MON-FRI").contains("Asia/Seoul");
    }

    @Test
    void status_off_mapped_returnsOff() {
        UserMappingEntity mapping = new UserMappingEntity("U1", "alice", "Alice");
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(mapping));

        String reply = service.status("U1");

        assertThat(reply).contains("OFF").doesNotContain("매핑 미등록");
    }

    @Test
    void status_unmapped_returnsOffWithGuide() {
        when(repository.findBySlackUserId("U-NEW")).thenReturn(Optional.empty());

        String reply = service.status("U-NEW");

        assertThat(reply).contains("OFF").contains("매핑 미등록");
    }
}
