package com.jirabot.slack.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

// STUDY: @EnableConfigurationProperties로 @ConfigurationProperties 빈 등록 (record에는 @Component 쓸 수 없음).
// STUDY: WebClient는 reactive HTTP client. block()을 호출하면 동기처럼 쓸 수 있다 (@Async 안에서 block 안전).
@Configuration
@EnableConfigurationProperties({ClaudeProperties.class, JiraProperties.class, IntentProperties.class,
        JiraWebhookProperties.class, NotifyProperties.class, ReminderProperties.class, NotionProperties.class,
        GitHubProperties.class})
public class WebClientConfig {

    // STUDY: HttpClient 레벨에서 connect/read timeout을 별도로 설정해야 한다.
    private static HttpClient httpClient(int readTimeoutSeconds) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS)));
    }

    @Bean
    public WebClient jiraWebClient(JiraProperties props) {
        String creds = (props.email() == null ? "" : props.email())
                + ":" + (props.apiToken() == null ? "" : props.apiToken());
        String basic = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        return WebClient.builder()
                .baseUrl(props.baseUrl() == null ? "http://localhost" : props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // STUDY: JQL 검색이 큰 결과(다수 이슈)를 한 페이지로 받으면 기본 256KB 버퍼를 넘겨
                //        DataBufferLimitException 으로 조용히 실패한다 → 8MB 로 상향.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(httpClient(30)))
                .build();
    }

    // STUDY: GitHub REST 용 WebClient. 토큰 없으면 Authorization 헤더 생략(기능 비활성 상태로도 빈 생성은 됨).
    //        fine-grained PAT 는 "Bearer <token>" 형식. api 버전 헤더는 GitHub 권장.
    @Bean
    public WebClient githubWebClient(GitHubProperties props) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(props.apiBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                // STUDY: PR 이 많은 repo(예: envector-msa 20개 ≈ 470KB)의 PR 목록이 기본 256KB 버퍼를 넘겨
                //        DataBufferLimitException 으로 조용히 빈 목록이 되던 버그 → 8MB 로 상향(jiraWebClient 와 동일).
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(httpClient(15)));
        if (props.token() != null && !props.token().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        return builder.build();
    }
}
