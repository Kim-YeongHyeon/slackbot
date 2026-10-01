package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.entity.DashboardUserEntity;
import com.jirabot.slack.repository.DashboardUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class DashboardUserDetailsServiceTest {

    private DashboardUserRepository repo;
    private DashboardUserDetailsService service;

    @BeforeEach
    void setUp() {
        repo = mock(DashboardUserRepository.class);
        service = new DashboardUserDetailsService(repo, "sol", "sol-pw");
    }

    @Test
    void admin_fromEnv_roleAdmin_noDbLookup() {
        UserDetails d = service.loadUserByUsername("sol");
        assertThat(d.getPassword()).isEqualTo("{noop}sol-pw");
        assertThat(d.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        verify(repo, never()).findByUsername("sol");
    }

    @Test
    void dbUser_roleUser_withStoredHash() {
        when(repo.findByUsername("kim")).thenReturn(Optional.of(
                new DashboardUserEntity("kim", "김", "{bcrypt}hash")));
        UserDetails d = service.loadUserByUsername("kim");
        assertThat(d.getPassword()).isEqualTo("{bcrypt}hash");
        assertThat(d.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(d.isEnabled()).isTrue();
    }

    @Test
    void disabledDbUser_reportedDisabled() {
        DashboardUserEntity u = new DashboardUserEntity("lee", "이", "{bcrypt}hash");
        u.setEnabled(false);
        when(repo.findByUsername("lee")).thenReturn(Optional.of(u));
        assertThat(service.loadUserByUsername("lee").isEnabled()).isFalse();
    }

    @Test
    void unknownUser_throws() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
