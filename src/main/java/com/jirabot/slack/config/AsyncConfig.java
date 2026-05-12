package com.jirabot.slack.config;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// STUDY: @EnableAsync 로 @Async 지원 활성화. 프록시 기반이라 self-invocation(같은 클래스 내부 호출)에는
// 비동기가 적용되지 않음 — 반드시 다른 빈의 메서드를 통해 호출해야 함.
// STUDY: AsyncConfigurer 를 구현하면 기본 executor/예외 핸들러를 커스터마이즈. @Async void 메서드는
// CompletableFuture 가 없으므로 throws 된 예외가 caller 로 전달되지 않고 이 handler 로만 간다.
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String SLACK_EXECUTOR = "slackTaskExecutor";
    private static final int QUEUE_CAPACITY = 50;
    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = SLACK_EXECUTOR)
    public Executor slackTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // STUDY: core=4/max=10/queue=50 — IO-bound(Claude/Jira HTTP) 기준. 큐가 먼저 차고 그 후 max 로 확장.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("slack-async-");
        // STUDY: AbortPolicy 기반 rejection — Slack 3초 ack 계약 보호. Controller 가 이미 200 반환 후
        // fire-and-forget @Async 로 내려오므로 CallerRunsPolicy 를 쓰면 tomcat http-nio 스레드가
        // 블록되어 ack 가 지연되고 Slack 이 재전송해 중복 이슈를 만든다. 포화 시엔 task 를 버리고
        // 경고 로그를 남겨 운영자가 용량/의존 외부 API 레이턴시를 조사하도록 한다.
        executor.setRejectedExecutionHandler((task, exec) -> {
            log.warn("slackTaskExecutor saturated, task rejected: pool={}/{} active={} queue={}/{}",
                    exec.getPoolSize(), exec.getMaximumPoolSize(),
                    exec.getActiveCount(), exec.getQueue().size(), QUEUE_CAPACITY);
            throw new RejectedExecutionException("slackTaskExecutor saturated");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return slackTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // STUDY: @Async void 메서드의 uncaught 예외(RejectedExecutionException 포함) 를 한 곳에서 관찰.
        // 예외를 던지지 않아 앱 지속 실행이 보장되고 warn 로그로 포화/실패 상태가 가시화된다.
        return (ex, method, params) ->
                log.warn("@Async uncaught in {}.{}: {}",
                        method.getDeclaringClass().getSimpleName(), method.getName(), ex.toString());
    }
}
