package com.jirabot.slack.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IssueActionSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// STUDY: skill-issue-action 추출 골든셋 평가 (opt-in). 실행:
//        ./gradlew test -Daction.eval=true --tests "*SkillActionEvalTest"
//        L15: test 프로파일의 cli-path=/bin/true 를 실값으로 오버라이드해야 실 모델을 잰다.
//        기대 필드가 명시된 것만 검사(null 필드는 무시) — action 정확도는 hard(>=0.9), 슬롯은 개별 리포트.
@SpringBootTest(properties = {"claude.cli-path=claude", "claude.timeout-seconds=20"})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "action.eval", matches = "true")
class SkillActionEvalTest {

    private static final String FIXTURE_PATH = "action-eval/cases.json";
    private static final Path REPORT_PATH = Path.of("build/reports/action-eval/report.txt");
    private static final double ACTION_ACC_THRESHOLD = 0.90;
    private static final double SLOT_ACC_THRESHOLD = 0.85;

    @Autowired
    private ClaudeApiClient claude;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void evaluateActionExtraction() throws IOException {
        List<Case> cases = loadFixture().cases();
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            IssueActionSpec s = claude.extractIssueAction(c.input());
            Outcome o = new Outcome(c, s);
            outcomes.add(o);
            System.out.printf("[%2d/%d] %-18s action=%-14s %s%n", i + 1, cases.size(),
                    c.id(), s.action(), o.allOk() ? "OK" : "MISS " + o.failures());
        }

        long actionOk = outcomes.stream().filter(Outcome::actionOk).count();
        long slotOk = outcomes.stream().filter(Outcome::slotsOk).count();
        double actionAcc = (double) actionOk / outcomes.size();
        double slotAcc = (double) slotOk / outcomes.size();

        StringBuilder sb = new StringBuilder();
        sb.append("Skill Issue-Action Extraction Report\n");
        sb.append(String.format("cases: %d, action acc: %.3f (>=%.2f), slot acc: %.3f (>=%.2f)%n",
                outcomes.size(), actionAcc, ACTION_ACC_THRESHOLD, slotAcc, SLOT_ACC_THRESHOLD));
        outcomes.stream().filter(o -> !o.allOk()).forEach(o ->
                sb.append(String.format("[%s] '%s' -> %s%n", o.c().id(), o.c().input(), o.failures())));
        System.out.println(sb);
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, sb.toString(), StandardCharsets.UTF_8);

        List<String> breaches = new ArrayList<>();
        if (actionAcc < ACTION_ACC_THRESHOLD) breaches.add("action acc " + actionAcc);
        if (slotAcc < SLOT_ACC_THRESHOLD) breaches.add("slot acc " + slotAcc);
        if (!breaches.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail("Threshold breach: " + breaches);
        }
    }

    record Outcome(Case c, IssueActionSpec s) {
        boolean actionOk() {
            return c.action().equals(s.action());
        }
        boolean slotsOk() {
            return eq(c.issueKey(), s.issueKey()) && eq(c.otherKey(), s.otherKey())
                    && eq(c.assignee(), s.assignee()) && eq(c.value(), s.value())
                    && eq(c.linkType(), s.linkType())
                    && eq(c.inwardKey(), s.inwardKey()) && eq(c.outwardKey(), s.outwardKey())
                    && eq(c.content(), s.content());
        }
        boolean allOk() { return actionOk() && slotsOk(); }
        private static boolean eq(String expected, String actual) {
            return expected == null || expected.equalsIgnoreCase(actual == null ? "" : actual.strip());
        }
        String failures() {
            List<String> f = new ArrayList<>();
            if (!actionOk()) f.add("action=" + s.action() + " expected=" + c.action());
            if (!slotsOk()) f.add("slots: got key=" + s.issueKey() + " other=" + s.otherKey()
                    + " assignee=" + s.assignee() + " value=" + s.value()
                    + " in=" + s.inwardKey() + " out=" + s.outwardKey() + " content=" + s.content());
            return String.join(" | ", f);
        }
    }

    private Fixture loadFixture() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) throw new IllegalStateException("Fixture not found: " + FIXTURE_PATH);
            return objectMapper.readValue(in, Fixture.class);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixture(List<Case> cases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Case(String id, String input, String action, String issueKey, String otherKey,
                String assignee, String value, String linkType, String inwardKey,
                String outwardKey, String content) {}
}
