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
        JiraWebhookProperties.class, NotifyProperties.class})
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
                .clientConnector(new ReactorClientHttpConnector(httpClient(30)))
                .build();
    }
}
