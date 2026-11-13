package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

// STUDY: application.yml 의 placeholder 기본값 연쇄를 실제 파일로 확인한다 (v0.0.96).
//        ci-alert.fix.model → ${CI_ALERT_FIX_MODEL:${ci-alert.model}} → ${CI_ALERT_MODEL:claude-sonnet-5}.
//        서버 환경변수가 결과를 바꾸지 않게 시스템 환경·속성 소스는 빼고 본다.
class CiAlertModelConfigTest {

    private static StandardEnvironment env(Map<String, Object> overrides) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return env;
    }

    @Test
    void alertAndFixDefaultToSonnet5_whileTheRestOfTheBotKeepsItsModel() throws Exception {
        StandardEnvironment env = env(Map.of());

        assertThat(env.getProperty("ci-alert.model")).isEqualTo("claude-sonnet-5");
        assertThat(env.getProperty("ci-alert.fix.model")).isEqualTo("claude-sonnet-5");
        assertThat(env.getProperty("claude.model")).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void envOverrides_alertModelFlowsToFix_unlessFixHasItsOwn() throws Exception {
        assertThat(env(Map.of("CI_ALERT_MODEL", "m-alert")).getProperty("ci-alert.fix.model")).isEqualTo("m-alert");

        StandardEnvironment both = env(Map.of("CI_ALERT_MODEL", "m-alert", "CI_ALERT_FIX_MODEL", "m-fix"));
        assertThat(both.getProperty("ci-alert.model")).isEqualTo("m-alert");
        assertThat(both.getProperty("ci-alert.fix.model")).isEqualTo("m-fix");
    }

    // 연속 실패 자동 수정 (v0.0.100) 은 Opus·effort high 를 따로 쓴다 — ci-alert.model 을 바꿔도 따라가지 않는다.
    @Test
    void codeFixDefaultsToOpusHigh_independentOfTheAlertModel() throws Exception {
        StandardEnvironment env = env(Map.of("CI_ALERT_MODEL", "m-alert"));

        assertThat(env.getProperty("ci-alert.code-fix.model")).isEqualTo("claude-opus-5-5");
        assertThat(env.getProperty("ci-alert.code-fix.effort")).isEqualTo("high");
        assertThat(env.getProperty("ci-alert.code-fix.min-streak")).isEqualTo("3");
        assertThat(env.getProperty("ci-alert.code-fix.go-check-steps[1]")).isEqualTo("Go vulnerability check (govulncheck)");
        assertThat(env.getProperty("ci-alert.code-fix.go-check-max-files")).isEqualTo("30");
        assertThat(env(Map.of("CI_ALERT_CODE_FIX_MODEL", "opus")).getProperty("ci-alert.code-fix.model")).isEqualTo("opus");
    }

    @Test
    void codeFixProperties_fillDefaults() {
        CiCodeFixProperties p = new CiCodeFixProperties(null, 0, " ", null, 0, 0, null, 0, 0, 0, 0, null, null, 0);

        assertThat(p.enabled()).isTrue();
        assertThat(p.minStreak()).isEqualTo(3);
        assertThat(p.model()).isEqualTo("claude-opus-5-5");
        assertThat(p.effort()).isEqualTo("high");
        assertThat(p.timeoutMinutes()).isEqualTo(30);
        assertThat(p.cooldownHours()).isEqualTo(24);
        assertThat(p.goCheckSteps()).containsExactly("Go version check", "Go vulnerability check (govulncheck)");
        assertThat(p.goCheckPromptFile()).isEqualTo("prompts/go-check-fix-agent.md");
        assertThat(p.goCheckMaxFiles()).isEqualTo(30);
        assertThat(new CiCodeFixProperties(true, 3, "m", "h", 1, 1, "p", 1, 1, 1, 1, List.of(), " ", 0).goCheckSteps())
                .as("빈 목록은 기본값").hasSize(2);
        assertThat(new CiCodeFixProperties(false, 5, "m", "", 1, 1, "p", 1, 1, 1, 1, List.of("x"), "g", 1).effort()).as("빈 effort 는 플래그 없음").isEmpty();
    }
}
