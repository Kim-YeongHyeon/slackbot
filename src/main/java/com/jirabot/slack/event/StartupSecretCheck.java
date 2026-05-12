package com.jirabot.slack.event;

import com.jirabot.slack.config.JiraWebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// STUDY: 부팅 직후 jira.webhook.enabled=true 이면서 secret 이 비어있는 케이스를 가시화.
//        @PostConstruct 대신 ApplicationReadyEvent 를 쓰는 이유: 모든 빈 초기화 후 실행이라 부수효과/순서 의존이 없음.
@Component
public class StartupSecretCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretCheck.class);

    private final JiraWebhookProperties props;

    public StartupSecretCheck(JiraWebhookProperties props) {
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.enabled() && (props.secret() == null || props.secret().isBlank())) {
            log.warn("jira.webhook.enabled=true but jira.webhook.secret is empty - all webhook requests will be rejected with 403");
        }
    }
}
