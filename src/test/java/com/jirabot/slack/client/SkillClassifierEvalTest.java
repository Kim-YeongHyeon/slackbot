package com.jirabot.slack.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.IssueClassification;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// STUDY: 분류 스킬(skill-bug/skill-story) 골든셋 평가 — IntentClassifierEvalTest 와 동일 패턴(opt-in).
//        실행: ./gradlew test -Dskill.eval=true --tests "*SkillClassifierEvalTest"
//        실제 claude CLI 호출(케이스당 ~4.5s, 20케이스 ≈ 2분). 스킬 파일 수정 후 반드시 전/후 비교(L12 정신).
//
//        검증 축:
//        - type 정확도 (hard, >= 0.90) — expectedTypes 중 하나
//        - SP 스케일 준수 {1,2,3,5,8} (hard, 100%) — docs/story-point-guide.md
//        - 제목 위생 (hard, 100%): 명령어구/이슈키 없음, <=120자, 비어있지 않음
//        - 도메인 토큰 보존 titleContains (>= 0.80) — 글로서리 검증
//        - SP 기대범위 spAllowed (>= 0.70) — SP 는 주관적이라 느슨하게
//        - summary 구조 마커 (>= 0.70) — 스킬이 지시한 구조(현상/완료 조건 등) 준수율
// STUDY: test 프로파일은 claude.cli-path=/bin/true(유닛테스트용 무해화)라 실 CLI 를 못 탄다.
//        eval 은 실제 모델 품질 측정이 목적이므로 cli-path/timeout 을 실값으로 오버라이드.
@SpringBootTest(properties = {"claude.cli-path=claude", "claude.timeout-seconds=20"})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "skill.eval", matches = "true")
class SkillClassifierEvalTest {

    private static final String FIXTURE_PATH = "skill-eval/cases.json";
    private static final Path REPORT_PATH = Path.of("build/reports/skill-eval/report.txt");

    private static final double TYPE_ACC_THRESHOLD = 0.90;
    private static final double TITLE_CONTAINS_THRESHOLD = 0.80;
    private static final double SP_ALLOWED_THRESHOLD = 0.70;
    private static final double MARKER_THRESHOLD = 0.70;

    private static final Set<Integer> VALID_SP = Set.of(1, 2, 3, 5, 8);
    // 제목에 남으면 안 되는 것: 요청/명령 어구, 이슈 키
    private static final Pattern TITLE_COMMAND_PHRASES = Pattern.compile(
            "(만들어\\s*줘|등록해\\s*줘|생성해\\s*줘|해줘|해\\s*주세요|부탁|please\\s+create|티켓\\s*만)");
    private static final Pattern TITLE_ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9]+-\\d+");

    @Autowired
    private ClaudeApiClient claude;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void evaluateSkills() throws IOException {
        Fixture fixture = loadFixture();
        List<Case> cases = fixture.cases();

        List<Outcome> outcomes = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            IntentResult hint = new IntentResult(c.skill(), 0.95, Map.of(), c.input());
            IssueClassification r = claude.classify(c.input(), hint);
            Outcome o = new Outcome(c, r);
            outcomes.add(o);
            System.out.printf("[%2d/%d] %-20s type=%-8s sp=%d %s | %s%n",
                    i + 1, cases.size(), c.id(),
                    r.type(), r.storyPoint(),
                    o.hardOk() ? "OK  " : "MISS",
                    truncate(r.title(), 60));
        }

        String report = render(outcomes);
        System.out.println();
        System.out.println(report);
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report, StandardCharsets.UTF_8);
        System.out.println("Report written to: " + REPORT_PATH.toAbsolutePath());

        List<String> breaches = breaches(outcomes);
        if (!breaches.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "Threshold breach(es):\n  - " + String.join("\n  - ", breaches));
        }
    }

    // ---------- 판정 ----------

    record Outcome(Case c, IssueClassification r) {
        boolean typeOk() {
            return r != null && r.type() != null && c.expectedTypes().contains(r.type().name());
        }
        boolean spScaleOk() {
            return r != null && VALID_SP.contains(r.storyPoint());
        }
        boolean spAllowedOk() {
            if (c.spAllowed() == null || c.spAllowed().isEmpty()) return true;
            return r != null && c.spAllowed().contains(r.storyPoint());
        }
        boolean titleHygieneOk() {
            if (r == null || r.title() == null || r.title().isBlank()) return false;
            String t = r.title();
            return t.length() <= 120
                    && !TITLE_COMMAND_PHRASES.matcher(t).find()
                    && !TITLE_ISSUE_KEY.matcher(t).find();
        }
        boolean titleContainsOk() {
            if (c.titleContains() == null || c.titleContains().isEmpty()) return true;
            if (r == null || r.title() == null) return false;
            String t = r.title().toLowerCase(Locale.ROOT);
            return c.titleContains().stream().allMatch(tok -> t.contains(tok.toLowerCase(Locale.ROOT)));
        }
        boolean markersOk() {
            if (c.summaryMarkers() == null || c.summaryMarkers().isEmpty()) return true;
            if (r == null || r.summary() == null) return false;
            return c.summaryMarkers().stream().allMatch(m -> r.summary().contains(m));
        }
        boolean hardOk() {
            return typeOk() && spScaleOk() && titleHygieneOk();
        }
    }

    private List<String> breaches(List<Outcome> outcomes) {
        List<String> breaches = new ArrayList<>();
        double typeAcc = ratio(outcomes, Outcome::typeOk);
        if (typeAcc < TYPE_ACC_THRESHOLD) {
            breaches.add(String.format("type accuracy %.3f < %.2f", typeAcc, TYPE_ACC_THRESHOLD));
        }
        outcomes.stream().filter(o -> !o.spScaleOk()).forEach(o ->
                breaches.add(String.format("[%s] SP %d not in {1,2,3,5,8}", o.c().id(),
                        o.r() == null ? -1 : o.r().storyPoint())));
        outcomes.stream().filter(o -> !o.titleHygieneOk()).forEach(o ->
                breaches.add(String.format("[%s] title hygiene fail: '%s'", o.c().id(),
                        o.r() == null ? null : o.r().title())));
        double contains = ratio(outcomes, Outcome::titleContainsOk);
        if (contains < TITLE_CONTAINS_THRESHOLD) {
            breaches.add(String.format("titleContains %.3f < %.2f", contains, TITLE_CONTAINS_THRESHOLD));
        }
        double spAllowed = ratio(outcomes, Outcome::spAllowedOk);
        if (spAllowed < SP_ALLOWED_THRESHOLD) {
            breaches.add(String.format("spAllowed %.3f < %.2f", spAllowed, SP_ALLOWED_THRESHOLD));
        }
        double markers = ratio(outcomes, Outcome::markersOk);
        if (markers < MARKER_THRESHOLD) {
            breaches.add(String.format("summaryMarkers %.3f < %.2f", markers, MARKER_THRESHOLD));
        }
        return breaches;
    }

    private String render(List<Outcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================================\n");
        sb.append("Skill Classifier Evaluation Report (skill-bug / skill-story)\n");
        sb.append("==========================================================\n");
        sb.append(String.format("Total cases        : %d%n", outcomes.size()));
        sb.append(String.format("type accuracy      : %.3f (>= %.2f)%n", ratio(outcomes, Outcome::typeOk), TYPE_ACC_THRESHOLD));
        sb.append(String.format("SP scale (1,2,3,5,8): %.3f (== 1.00)%n", ratio(outcomes, Outcome::spScaleOk)));
        sb.append(String.format("title hygiene      : %.3f (== 1.00)%n", ratio(outcomes, Outcome::titleHygieneOk)));
        sb.append(String.format("titleContains      : %.3f (>= %.2f)%n", ratio(outcomes, Outcome::titleContainsOk), TITLE_CONTAINS_THRESHOLD));
        sb.append(String.format("spAllowed          : %.3f (>= %.2f)%n", ratio(outcomes, Outcome::spAllowedOk), SP_ALLOWED_THRESHOLD));
        sb.append(String.format("summaryMarkers     : %.3f (>= %.2f)%n", ratio(outcomes, Outcome::markersOk), MARKER_THRESHOLD));
        sb.append("\n--- Failures ---\n");
        boolean any = false;
        for (Outcome o : outcomes) {
            List<String> fails = new ArrayList<>();
            if (!o.typeOk()) fails.add("type=" + (o.r() == null ? null : o.r().type())
                    + " expected=" + o.c().expectedTypes());
            if (!o.spScaleOk()) fails.add("sp-scale=" + (o.r() == null ? -1 : o.r().storyPoint()));
            if (!o.spAllowedOk()) fails.add("sp=" + o.r().storyPoint() + " allowed=" + o.c().spAllowed());
            if (!o.titleHygieneOk()) fails.add("title='" + (o.r() == null ? null : o.r().title()) + "'");
            if (!o.titleContainsOk()) fails.add("titleContains=" + o.c().titleContains()
                    + " got='" + (o.r() == null ? null : o.r().title()) + "'");
            if (!o.markersOk()) fails.add("markers=" + o.c().summaryMarkers());
            if (!fails.isEmpty()) {
                any = true;
                sb.append(String.format("[%s] '%s'%n    %s%n", o.c().id(),
                        truncate(o.c().input(), 70), String.join(" | ", fails)));
            }
        }
        if (!any) sb.append("(none)\n");
        return sb.toString();
    }

    private static double ratio(List<Outcome> outcomes, java.util.function.Predicate<Outcome> p) {
        if (outcomes.isEmpty()) return 0.0;
        return (double) outcomes.stream().filter(p).count() / outcomes.size();
    }

    private Fixture loadFixture() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + FIXTURE_PATH);
            }
            return objectMapper.readValue(in, Fixture.class);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixture(List<Case> cases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Case(String id, String skill, String input, List<String> expectedTypes,
                List<Integer> spAllowed, List<String> titleContains, List<String> summaryMarkers) {}
}
