package com.jirabot.slack.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// STUDY: 필수 설정 fail-fast 검증. 빠지면 "첫 Slack 이벤트가 403" / "첫 Jira 호출이 401" 처럼
//        늦고 모호하게 실패하던 것을, 기동 시점에 누락 키 목록과 함께 즉시 실패시킨다.
//        (Go 봇의 SLACK_SIGNING_SECRET env-parity 체크와 같은 철학 — 판매 제품의 설치 실패는 친절해야 한다.)
// STUDY: BeanFactoryPostProcessor — 일반 빈(DataSource/Flyway 포함)이 만들어지기 전 단계에서 실행된다.
//        @PostConstruct 방식이면 DB 연결 실패가 먼저 터져 진짜 원인(키 누락)이 가려진다(음성 테스트로 확인).
@Component
public class StartupEnvValidator implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(StartupEnvValidator.class);

    // 프로퍼티 키 → 사용자가 .env 에서 채워야 하는 환경변수 이름.
    private static final Map<String, String> REQUIRED = Map.of(
            "slack.bot-token", "SLACK_BOT_TOKEN",
            "slack.signing-secret", "SLACK_SIGNING_SECRET",
            "jira.base-url", "JIRA_BASE_URL",
            "jira.email", "JIRA_EMAIL",
            "jira.api-token", "JIRA_API_TOKEN",
            "jira.project-key", "JIRA_PROJECT_KEY");

    // STUDY: BFPP 는 컨테이너 극초기에 인스턴스화되어 생성자 주입을 못 쓴다(No default constructor 에러).
    //        Environment 는 refresh 시점에 이미 싱글톤으로 등록돼 있으므로 beanFactory 에서 직접 꺼낸다.
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        validate(beanFactory.getBean(Environment.class));
    }

    void validate(Environment env) {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, envVar) -> {
            String value = env.getProperty(property);
            if (value == null || value.isBlank()) {
                missing.add(envVar + " (" + property + ")");
            }
        });
        if (!missing.isEmpty()) {
            String message = "필수 환경변수가 비어 있어 기동을 중단합니다 — .env 를 확인하세요: "
                    + String.join(", ", missing.stream().sorted().toList());
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("Startup env validation passed ({} required keys)", REQUIRED.size());
    }
}
