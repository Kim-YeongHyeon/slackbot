package com.jirabot.slack.config;

import java.nio.file.Files;
import java.nio.file.Path;
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
    // dashboard.* 는 v0.0.73 부터 필수 — 비어 있으면 대시보드가 무인증으로 뜨는 대신 기동을 막는다.
    private static final Map<String, String> REQUIRED = Map.of(
            "slack.bot-token", "SLACK_BOT_TOKEN",
            "slack.signing-secret", "SLACK_SIGNING_SECRET",
            "jira.base-url", "JIRA_BASE_URL",
            "jira.email", "JIRA_EMAIL",
            "jira.api-token", "JIRA_API_TOKEN",
            "jira.project-key", "JIRA_PROJECT_KEY",
            "dashboard.user", "DASHBOARD_USER",
            "dashboard.password", "DASHBOARD_PASSWORD");

    // STUDY: BFPP 는 컨테이너 극초기에 인스턴스화되어 생성자 주입을 못 쓴다(No default constructor 에러).
    //        Environment 는 refresh 시점에 이미 싱글톤으로 등록돼 있으므로 beanFactory 에서 직접 꺼낸다.
    // 프롬프트 폴더 — 코드 상수로 쓰는 프롬프트(ClaudeApiClientImpl 등)는 모두 이 안에 있다.
    static final String PROMPTS_DIR = "prompts";

    // 환경변수로 바꿀 수 있는 프롬프트 파일 경로 키. 바꾼 경로가 틀려도 기동 시점에 드러나게 한다.
    static final List<String> PROMPT_FILE_KEYS = List.of(
            "claude.intent.prompt-file",
            "ci-alert.fix.prompt-file",
            "ci-alert.code-fix.prompt-file",
            "ci-alert.code-fix.go-check-prompt-file");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Environment env = beanFactory.getBean(Environment.class);
        validate(env);
        // STUDY: Path.of("") 는 JVM 작업 폴더(system property `user.dir`)다. `java -jar` 를 실행한 셸의 현재 폴더가
        //        그대로 들어오고, 실행 중에는 바꿀 수 없다. 상대 경로 파일(Path.of("prompts/x.md"), claude CLI 에 넘기는
        //        "--system-prompt-file prompts/x.md")은 모두 이 폴더 기준으로 풀린다.
        validatePromptFiles(env, Path.of("").toAbsolutePath());
    }

    // STUDY: 작업 폴더가 틀려도 웹·DB 는 정상이라 /actuator/health 는 UP 이다. 실패는 claude 를 부를 때마다
    //        조용히 일어나고(2026-11-19~23, lessons L27), 사용자에게는 "이해하지 못했어요"로만 보인다.
    //        그래서 health 대신 기동 시점에 파일 존재를 확인해 즉시 실패시킨다.
    void validatePromptFiles(Environment env, Path workDir) {
        List<String> missing = new ArrayList<>();
        if (!Files.isDirectory(workDir.resolve(PROMPTS_DIR))) {
            missing.add(PROMPTS_DIR + "/");
        }
        for (String key : PROMPT_FILE_KEYS) {
            String value = env.getProperty(key);
            if (value == null || value.isBlank()) {
                continue; // 비어 있으면 각 Properties 의 기본값(prompts/ 아래)을 쓴다 — 폴더 점검이 맡는다
            }
            // STUDY: Path.resolve — 인자가 절대 경로면 그대로, 상대 경로면 workDir 뒤에 붙인다.
            if (!Files.isRegularFile(workDir.resolve(value))) {
                missing.add(value + " (" + key + ")");
            }
        }
        if (!missing.isEmpty()) {
            String message = "프롬프트 파일을 찾을 수 없어 기동을 중단합니다 — 작업 폴더: " + workDir
                    + ", 없음: " + String.join(", ", missing)
                    + ". prompts/ 가 있는 앱 폴더에서 실행하세요.";
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("Startup prompt check passed (workDir={})", workDir);
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
