package com.jirabot.slack.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;

// STUDY: 데코레이터 패턴 — 기존 PasswordEncoder 를 감싸 matches() 성공 결과만 캐시한다 (v0.0.79).
//        Basic 인증은 매 요청마다 비밀번호를 검증하는데 BCrypt 는 의도적으로 느린(수십 ms) 해시라,
//        탭 로드·아티팩트 자산마다 반복하면 체감 지연이 생긴다.
//        캐시 키 = SHA-256(raw + "\0" + encoded): 비밀번호가 바뀌면 encoded 가 달라져 자동 무효,
//        실패는 캐시하지 않아 비밀번호 대입이 캐시로 빨라지지 않는다. 원문 비밀번호는 저장하지 않는다.
public class CachingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Cache<String, Boolean> verified;

    public CachingPasswordEncoder(PasswordEncoder delegate, Duration ttl, long maxSize) {
        this.delegate = delegate;
        this.verified = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String key = key(rawPassword, encodedPassword);
        if (verified.getIfPresent(key) != null) {
            return true;
        }
        boolean ok = delegate.matches(rawPassword, encodedPassword);
        if (ok) {
            verified.put(key, Boolean.TRUE);
        }
        return ok;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private static String key(CharSequence raw, String encoded) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(raw.toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(encoded.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
