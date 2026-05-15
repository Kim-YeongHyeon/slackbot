package com.jirabot.slack.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IntentResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// STUDY: @EnabledIfSystemProperty 로 기본 ./gradlew test 시 자동 skip.
//        실행: ./gradlew test -Dintent.eval=true --tests "*IntentClassifierEvalTest"
//        실제 claude CLI 를 호출하므로 OAuth 인증 + claude 가 PATH 에 있어야 함.
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "intent.eval", matches = "true")
class IntentClassifierEvalTest {

    private static final double OVERALL_THRESHOLD = 0.95;
    private static final double PER_INTENT_RECALL_THRESHOLD = 0.75;
    private static final String FIXTURE_PATH = "intent-eval/cases.json";
    private static final Path REPORT_PATH = Path.of("build/reports/intent-eval/report.txt");

    @Autowired
    private IntentClassifier classifier;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void evaluateAccuracy() throws IOException {
        Fixture fixture = loadFixture();
        List<Case> cases = fixture.cases();

        List<Outcome> outcomes = new ArrayList<>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            IntentResult result = classifier.classify(c.input());
            Outcome outcome = new Outcome(c, result);
            outcomes.add(outcome);
            System.out.printf("[%3d/%d] %-13s %s  predicted=%-16s expected=%-16s %s%n",
                    i + 1, cases.size(),
                    c.id(),
                    outcome.passed() ? "OK  " : "MISS",
                    safeIntent(result),
                    c.expected(),
                    truncate(c.input(), 60));
        }

        Report report = buildReport(outcomes);
        String rendered = renderReport(report);
        System.out.println();
        System.out.println(rendered);
        writeReport(rendered);

        List<String> breaches = collectThresholdBreaches(report);
        if (!breaches.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "Threshold breach(es):\n  - " + String.join("\n  - ", breaches));
        }
    }

    private Fixture loadFixture() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + FIXTURE_PATH);
            }
            return objectMapper.readValue(in, Fixture.class);
        }
    }

    private Report buildReport(List<Outcome> outcomes) {
        int total = outcomes.size();
        int correct = (int) outcomes.stream().filter(Outcome::passed).count();
        double overallAcc = total == 0 ? 0.0 : (double) correct / total;

        TreeSet<String> intents = new TreeSet<>();
        outcomes.forEach(o -> {
            intents.add(o.c().expected());
            intents.add(safeIntent(o.result()));
        });

        Map<String, IntentStats> perIntent = new LinkedHashMap<>();
        for (String intent : intents) {
            int support = (int) outcomes.stream()
                    .filter(o -> intent.equals(o.c().expected())).count();
            int predicted = (int) outcomes.stream()
                    .filter(o -> intent.equals(safeIntent(o.result()))).count();
            int truePositive = (int) outcomes.stream()
                    .filter(o -> intent.equals(o.c().expected())
                            && intent.equals(safeIntent(o.result())))
                    .count();
            double precision = predicted == 0 ? 0.0 : (double) truePositive / predicted;
            double recall = support == 0 ? Double.NaN : (double) truePositive / support;
            double f1 = (precision + recall) == 0 || Double.isNaN(recall)
                    ? 0.0
                    : 2 * precision * recall / (precision + recall);
            perIntent.put(intent, new IntentStats(intent, support, predicted, truePositive,
                    precision, recall, f1));
        }

        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        for (String row : intents) {
            Map<String, Integer> cols = new TreeMap<>();
            for (String col : intents) cols.put(col, 0);
            confusion.put(row, cols);
        }
        for (Outcome o : outcomes) {
            confusion.get(o.c().expected())
                    .merge(safeIntent(o.result()), 1, Integer::sum);
        }

        List<Outcome> failures = outcomes.stream()
                .filter(o -> !o.passed())
                .sorted(Comparator.comparing(o -> o.c().id()))
                .toList();

        return new Report(total, correct, overallAcc, perIntent, confusion, failures);
    }

    private String renderReport(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================================\n");
        sb.append("Intent Classifier Evaluation Report\n");
        sb.append("==========================================================\n");
        sb.append(String.format("Total cases   : %d%n", r.total()));
        sb.append(String.format("Correct       : %d%n", r.correct()));
        sb.append(String.format("Overall acc.  : %.3f (threshold %.2f, %s)%n",
                r.overallAccuracy(), OVERALL_THRESHOLD,
                r.overallAccuracy() >= OVERALL_THRESHOLD ? "PASS" : "FAIL"));
        sb.append("\n");

        sb.append("--- Per-intent metrics ---\n");
        sb.append(String.format("%-18s %7s %9s %10s %8s %6s%n",
                "intent", "support", "predicted", "precision", "recall", "F1"));
        for (IntentStats s : r.perIntent().values()) {
            sb.append(String.format("%-18s %7d %9d %10.3f %8s %6.3f%n",
                    s.intent(), s.support(), s.totalPredictions(),
                    s.precision(),
                    Double.isNaN(s.recall()) ? "n/a" : String.format("%.3f", s.recall()),
                    s.f1()));
        }
        sb.append("\n");

        sb.append("--- Confusion matrix (rows=expected, cols=predicted) ---\n");
        List<String> labels = new ArrayList<>(r.confusionMatrix().keySet());
        sb.append(String.format("%-18s", ""));
        for (String c : labels) sb.append(String.format("%5s ", abbreviate(c)));
        sb.append("\n");
        for (String row : labels) {
            sb.append(String.format("%-18s", row));
            Map<String, Integer> cols = r.confusionMatrix().get(row);
            for (String c : labels) sb.append(String.format("%5d ", cols.getOrDefault(c, 0)));
            sb.append("\n");
        }
        sb.append("(abbrev legend: ")
                .append(String.join(", ",
                        labels.stream().map(l -> abbreviate(l) + "=" + l).toList()))
                .append(")\n\n");

        sb.append("--- Failures (").append(r.failures().size()).append(") ---\n");
        for (Outcome o : r.failures()) {
            sb.append(String.format("[%s] '%s'%n", o.c().id(), o.c().input()));
            sb.append(String.format("    expected=%s, predicted=%s, conf=%.2f%n",
                    o.c().expected(), safeIntent(o.result()), o.result().confidence()));
            if (o.c().note() != null && !o.c().note().isBlank()) {
                sb.append("    note: ").append(o.c().note()).append("\n");
            }
        }
        if (r.failures().isEmpty()) sb.append("(none)\n");
        sb.append("\n");

        sb.append("--- Threshold check ---\n");
        sb.append(String.format("Overall   : %.3f >= %.2f %s%n",
                r.overallAccuracy(), OVERALL_THRESHOLD,
                r.overallAccuracy() >= OVERALL_THRESHOLD ? "PASS" : "FAIL"));
        sb.append(String.format("Per-intent recall >= %.2f:%n", PER_INTENT_RECALL_THRESHOLD));
        for (IntentStats s : r.perIntent().values()) {
            if (Double.isNaN(s.recall())) continue;
            sb.append(String.format("  %-18s %.3f %s%n",
                    s.intent(), s.recall(),
                    s.recall() >= PER_INTENT_RECALL_THRESHOLD ? "PASS" : "FAIL"));
        }

        return sb.toString();
    }

    private void writeReport(String text) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, text, StandardCharsets.UTF_8);
        System.out.println("Report written to: " + REPORT_PATH.toAbsolutePath());
    }

    private List<String> collectThresholdBreaches(Report r) {
        List<String> breaches = new ArrayList<>();
        if (r.overallAccuracy() < OVERALL_THRESHOLD) {
            breaches.add(String.format("overall accuracy %.3f < %.2f",
                    r.overallAccuracy(), OVERALL_THRESHOLD));
        }
        for (IntentStats s : r.perIntent().values()) {
            if (Double.isNaN(s.recall())) continue;
            if (s.recall() < PER_INTENT_RECALL_THRESHOLD) {
                breaches.add(String.format("recall for '%s' = %.3f < %.2f",
                        s.intent(), s.recall(), PER_INTENT_RECALL_THRESHOLD));
            }
        }
        return breaches;
    }

    private static String safeIntent(IntentResult r) {
        return r == null || r.intent() == null ? "unknown" : r.intent();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String abbreviate(String intent) {
        return switch (intent) {
            case "search" -> "srch";
            case "register_story" -> "stry";
            case "register_bug" -> "bug";
            case "statistics" -> "stat";
            case "my_tasks" -> "mine";
            case "sprint_report" -> "sprt";
            case "sync_request" -> "sync";
            case "complete_issue" -> "done";
            case "reminder_toggle" -> "rmnd";
            case "skip" -> "skip";
            case "unknown" -> "unk";
            default -> intent.length() <= 4 ? intent : intent.substring(0, 4);
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixture(List<Case> cases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Case(String id, String input, String expected, String note) {}

    record Outcome(Case c, IntentResult result) {
        boolean passed() {
            return Objects.equals(c.expected(), result == null ? null : result.intent());
        }
    }

    record IntentStats(String intent, int support, int totalPredictions, int correctPredictions,
                       double precision, double recall, double f1) {}

    record Report(int total, int correct, double overallAccuracy,
                  Map<String, IntentStats> perIntent,
                  Map<String, Map<String, Integer>> confusionMatrix,
                  List<Outcome> failures) {}
}
