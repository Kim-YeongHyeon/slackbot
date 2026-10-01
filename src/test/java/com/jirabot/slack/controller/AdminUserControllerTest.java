package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.config.DashboardUserDetailsService;
import com.jirabot.slack.entity.DashboardUserEntity;
import com.jirabot.slack.repository.DashboardUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

class AdminUserControllerTest {

    private DashboardUserRepository repo;
    private PasswordEncoder encoder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repo = mock(DashboardUserRepository.class);
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc = standaloneSetup(new AdminUserController(repo, encoder,
                new DashboardUserDetailsService(repo, "sol", "sol-pw"))).build();
    }

    private static String json(String username, String displayName, String password) {
        return String.format("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"%s\"}",
                username, displayName, password);
    }

    @Test
    void create_hashesPassword_andNeverReturnsIt() throws Exception {
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("kim", "김영현", "simple1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("kim"))
                .andExpect(jsonPath("$.displayName").value("김영현"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("bcrypt").doesNotContain("simple1"));

        ArgumentCaptor<DashboardUserEntity> cap = ArgumentCaptor.forClass(DashboardUserEntity.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getPasswordHash()).startsWith("{bcrypt}");
        assertThat(encoder.matches("simple1", cap.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void create_invalidUsername_rejected() throws Exception {
        for (String bad : new String[]{"ab", "Kim", "김영현", "has space", "x".repeat(31)}) {
            mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                            .content(json(bad, "이름", "pw1234")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }
        verify(repo, never()).save(any());
    }

    @Test
    void create_adminUsername_reserved() throws Exception {
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("sol", "가짜관리자", "pw1234")))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    void create_duplicate_rejected() throws Exception {
        when(repo.existsByUsername("kim")).thenReturn(true);
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("kim", "김", "pw1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 아이디입니다: kim"));
    }

    @Test
    void create_shortOrMissingPassword_or_blankName_rejected() throws Exception {
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("kim", "김", "123")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("kim", "  ", "pw1234")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("kim", "김", "x".repeat(73))))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    void patch_passwordReset_changesHash() throws Exception {
        DashboardUserEntity u = new DashboardUserEntity("kim", "김", encoder.encode("old-pw"));
        when(repo.findById(3L)).thenReturn(Optional.of(u));

        mockMvc.perform(patch("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"new-pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(encoder.matches("new-pw", u.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-pw", u.getPasswordHash())).isFalse();
    }

    @Test
    void patch_disable_and_enable() throws Exception {
        DashboardUserEntity u = new DashboardUserEntity("kim", "김", "{bcrypt}h");
        when(repo.findById(3L)).thenReturn(Optional.of(u));

        mockMvc.perform(patch("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(u.isEnabled()).isFalse();
        assertThat(u.getPasswordHash()).isEqualTo("{bcrypt}h"); // 다른 필드는 그대로
    }

    @Test
    void patch_invalidPassword_rejected_keepsHash() throws Exception {
        DashboardUserEntity u = new DashboardUserEntity("kim", "김", "{bcrypt}h");
        when(repo.findById(3L)).thenReturn(Optional.of(u));
        mockMvc.perform(patch("/api/admin/users/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"1\"}"))
                .andExpect(status().isBadRequest());
        assertThat(u.getPasswordHash()).isEqualTo("{bcrypt}h");
    }

    @Test
    void patch_and_delete_missing_404() throws Exception {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        mockMvc.perform(patch("/api/admin/users/9").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/admin/users/9")).andExpect(status().isNotFound());
    }

    @Test
    void delete_existing() throws Exception {
        DashboardUserEntity u = new DashboardUserEntity("kim", "김", "{bcrypt}h");
        when(repo.findById(3L)).thenReturn(Optional.of(u));
        mockMvc.perform(delete("/api/admin/users/3")).andExpect(status().isOk());
        verify(repo).delete(u);
    }

    @Test
    void list_hidesHashes() throws Exception {
        when(repo.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
                new DashboardUserEntity("kim", "김", "{bcrypt}secret-hash")));
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("kim"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-hash"));
    }
}
