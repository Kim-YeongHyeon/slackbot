package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class CachingPasswordEncoderTest {

    @Test
    void successfulMatch_isCached_delegateCalledOnce() {
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        when(delegate.matches(any(), anyString())).thenReturn(true);
        CachingPasswordEncoder enc = new CachingPasswordEncoder(delegate, Duration.ofMinutes(10), 100);

        assertThat(enc.matches("pw", "{bcrypt}h1")).isTrue();
        assertThat(enc.matches("pw", "{bcrypt}h1")).isTrue();
        verify(delegate, times(1)).matches("pw", "{bcrypt}h1");
    }

    @Test
    void failedMatch_isNotCached() {
        // 실패를 캐시하면 안 된다 — 이후 올바른 비번 설정 시 오판하거나, 대입 공격이 빨라지지 않게.
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        when(delegate.matches(any(), anyString())).thenReturn(false);
        CachingPasswordEncoder enc = new CachingPasswordEncoder(delegate, Duration.ofMinutes(10), 100);

        assertThat(enc.matches("bad", "{bcrypt}h1")).isFalse();
        assertThat(enc.matches("bad", "{bcrypt}h1")).isFalse();
        verify(delegate, times(2)).matches("bad", "{bcrypt}h1");
    }

    @Test
    void passwordReset_changesHash_soOldPasswordIsReverified() {
        // 비밀번호 초기화 → 저장 해시가 바뀜 → 캐시 키가 달라져 옛 비번은 실제 검증으로 실패해야 한다.
        CachingPasswordEncoder enc = new CachingPasswordEncoder(
                PasswordEncoderFactories.createDelegatingPasswordEncoder(), Duration.ofMinutes(10), 100);
        String oldHash = enc.encode("old-pw");
        assertThat(enc.matches("old-pw", oldHash)).isTrue();

        String newHash = enc.encode("new-pw");
        assertThat(enc.matches("old-pw", newHash)).isFalse();
        assertThat(enc.matches("new-pw", newHash)).isTrue();
    }

    @Test
    void realDelegate_supportsNoopAndBcrypt() {
        CachingPasswordEncoder enc = new CachingPasswordEncoder(
                PasswordEncoderFactories.createDelegatingPasswordEncoder(), Duration.ofMinutes(10), 100);
        assertThat(enc.encode("x")).startsWith("{bcrypt}");
        assertThat(enc.matches("sol", "{noop}sol")).isTrue();
        assertThat(enc.matches("nope", "{noop}sol")).isFalse();
        assertThat(enc.matches(null, "{noop}sol")).isFalse();
    }
}
