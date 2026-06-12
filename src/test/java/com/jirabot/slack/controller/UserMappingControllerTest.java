package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class UserMappingControllerTest {

    private UserMappingRepository repository;
    private JiraApiClient jiraApiClient;
    private SlackNotifier slackNotifier;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(UserMappingRepository.class);
        jiraApiClient = mock(JiraApiClient.class);
        slackNotifier = mock(SlackNotifier.class);
        mockMvc = standaloneSetup(new UserMappingController(repository, jiraApiClient, slackNotifier))
                .build();
    }

    @Test
    void register_resolvesAccountIdAndSlackName() throws Exception {
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.empty());
        when(jiraApiClient.findAccountId("홍길동")).thenReturn("acc-77");
        when(slackNotifier.getUserRealName("U1")).thenReturn("길동 슬랙");

        mockMvc.perform(post("/api/user-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slackUserId\":\"U1\",\"jiraDisplayName\":\"홍길동\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("created"))
                .andExpect(jsonPath("$.jiraAccountId").value("acc-77"));

        ArgumentCaptor<UserMappingEntity> saved = ArgumentCaptor.forClass(UserMappingEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getJiraAccountId()).isEqualTo("acc-77");
        assertThat(saved.getValue().getSlackDisplayName()).isEqualTo("길동 슬랙");
    }

    @Test
    void register_unknownJiraUser_savesWithWarning() throws Exception {
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.empty());
        when(jiraApiClient.findAccountId("미상")).thenReturn(null);

        mockMvc.perform(post("/api/user-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slackUserId\":\"U1\",\"jiraDisplayName\":\"미상\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").exists());

        verify(repository).save(any(UserMappingEntity.class));
    }

    @Test
    void register_missingFields_is400() throws Exception {
        mockMvc.perform(post("/api/user-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slackUserId\":\"U1\"}"))
                .andExpect(status().isBadRequest());

        verify(repository, never()).save(any());
    }

    @Test
    void delete_existing_removes() throws Exception {
        UserMappingEntity entity = new UserMappingEntity("U1", "s", "홍길동", "acc");
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(entity));

        mockMvc.perform(delete("/api/user-mappings/U1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));

        verify(repository).delete(entity);
    }

    @Test
    void delete_missing_is404() throws Exception {
        when(repository.findBySlackUserId("UX")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/user-mappings/UX"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patch_togglesOnlyProvidedFields() throws Exception {
        UserMappingEntity entity = new UserMappingEntity("U1", "s", "홍길동", "acc");
        entity.setReminderEnabled(true);   // assignDm 기본 true
        when(repository.findBySlackUserId("U1")).thenReturn(Optional.of(entity));

        mockMvc.perform(patch("/api/user-mappings/U1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignDmEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignDmEnabled").value(false))
                .andExpect(jsonPath("$.reminderEnabled").value(true));

        assertThat(entity.isAssignDmEnabled()).isFalse();
        assertThat(entity.isReminderEnabled()).isTrue();   // 미포함 키는 유지
        verify(repository).save(entity);
    }

    @Test
    void patch_missing_is404() throws Exception {
        when(repository.findBySlackUserId("UX")).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/user-mappings/UX")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderEnabled\":true}"))
                .andExpect(status().isNotFound());
    }
}
