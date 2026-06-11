package com.jirabot.slack.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// STUDY: @EnableCaching — @Cacheable/@CacheEvict 어노테이션을 AOP 프록시로 활성화.
//        Caffeine 은 Spring Boot 가 1순위로 지원하는 인메모리 캐시 (TTL/최대 크기 지원).
//        활성 스프린트는 2주마다 바뀌는 데이터인데 getActiveSprint() 가 호출마다
//        Jira 왕복 2회(보드 조회 + 스프린트 조회)를 하므로 짧은 TTL 캐시로 흡수한다.
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ACTIVE_SPRINT_CACHE = "activeSprint";

    // STUDY: TTL 5분 — 스프린트 시작/종료가 캐시에 반영되기까지의 최대 지연.
    //        버튼 전환(moveToActiveSprint)·리마인더가 참조하는 수준에서 충분히 신선하다.
    private static final Duration ACTIVE_SPRINT_TTL = Duration.ofMinutes(5);

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(ACTIVE_SPRINT_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(ACTIVE_SPRINT_TTL)
                .maximumSize(10));
        return manager;
    }
}
