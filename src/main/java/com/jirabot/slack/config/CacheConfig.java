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
    // 대시보드 PR 현황 — repo 수만큼 GitHub 왕복이라 캐시 필수 (rate limit + 응답속도).
    public static final String OPEN_PRS_CACHE = "openPrs";
    // 버그 지식베이스 — Notion 전체 조회(페이지네이션)라 느림 → 캐시.
    public static final String BUG_KNOWLEDGE_CACHE = "bugKnowledge";
    // 이슈 링크 타입(Blocks/Relates/Duplicate…) — 사이트 설정이라 거의 안 변함. 링크 생성마다 재조회 회피.
    public static final String ISSUE_LINK_TYPES_CACHE = "issueLinkTypes";

    // STUDY: TTL 5분 — 스프린트 시작/종료, PR 생성/머지가 캐시에 반영되기까지의 최대 지연.
    //        두 캐시 모두 "자주 읽고 천천히 변하는" 외부 API 응답이라 동일 정책을 공유한다.
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                ACTIVE_SPRINT_CACHE, OPEN_PRS_CACHE, BUG_KNOWLEDGE_CACHE, ISSUE_LINK_TYPES_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(10));
        return manager;
    }
}
