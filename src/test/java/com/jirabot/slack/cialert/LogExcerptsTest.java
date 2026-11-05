package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogExcerptsTest {

    private static String log(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append("2026-11-05T05:08:29.2422032Z ").append(l).append('\n');
        }
        return sb.toString();
    }

    @Test
    void cleanLines_stripsTimestampsAndAnsi() {
        List<String> lines = LogExcerpts.cleanLines(log("\u001B[31mred text\u001B[0m", "plain"));

        assertThat(lines).containsExactly("red text", "plain");
    }

    @Test
    void cleanLines_blankLog_isNoLines() {
        assertThat(LogExcerpts.cleanLines("")).isEmpty();
        assertThat(LogExcerpts.cleanLines("  \n")).isEmpty();
        assertThat(LogExcerpts.cleanLines(null)).isEmpty();
    }

    @Test
    void cleanLines_cutsVeryLongLines() {
        List<String> lines = LogExcerpts.cleanLines("x".repeat(1000));

        assertThat(lines.get(0)).hasSize(401).endsWith("…");
    }

    @Test
    void excerpt_keepsContextBeforeErrorAndTail() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            lines.add("line " + i);
        }
        lines.set(100, "fatal: cannot connect to registry");
        lines.set(101, "##[error]Process completed with exit code 1.");

        String ex = LogExcerpts.excerpt(lines, 100_000);

        assertThat(ex).contains("line 81", "fatal: cannot connect to registry", "##[error]Process completed")
                .contains("line 299").doesNotContain("line 80\n").doesNotContain("line 150\n");
        assertThat(ex).contains("…\n");   // 끊긴 구간 표시
    }

    @Test
    void excerpt_respectsMaxCharsKeepingTheEnd() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            lines.add("line " + i + " " + "y".repeat(50));
        }

        String ex = LogExcerpts.excerpt(lines, 500);

        assertThat(ex).hasSizeLessThanOrEqualTo(501).startsWith("…").contains("line 59");
    }

    @Test
    void errorContext_takesMeaningfulLinesBeforeFirstError() {
        List<String> lines = List.of("setup", "##[group]Run make", "real cause here", "##[endgroup]",
                "##[error]Process completed with exit code 2.", "post step");

        List<String> ctx = LogExcerpts.errorContext(lines);

        assertThat(ctx).containsExactly("setup", "real cause here", "##[error]Process completed with exit code 2.");
    }

    @Test
    void errorContext_withoutErrorMarker_usesLastLines() {
        List<String> ctx = LogExcerpts.errorContext(List.of("a", "b", "c", "d", "e", "f", "g"));

        assertThat(ctx).containsExactly("c", "d", "e", "f", "g");
    }

    @Test
    void fingerprint_sameCauseWithDifferentNumbersAndHashes_isEqual() {
        String a = LogExcerpts.fingerprint("evi", "evi main", List.of("test → Build"),
                List.of("lookup proxy on 10.43.0.10:53: read udp 10.42.1.14:40211 i/o timeout",
                        "pull sha256:73235304743341e3d8968c45f2d94b226ebecedf1f2e9864d45b92d86f85f38d failed",
                        "/home/runner/_work/_temp/7347336e-e947.sh: line 5: python: command not found"));
        String b = LogExcerpts.fingerprint("evi", "evi main", List.of("test → Build"),
                List.of("lookup proxy on 10.43.0.11:53: read udp 10.42.9.1:51000 i/o timeout",
                        "pull sha256:ffff5304743341e3d8968c45f2d94b226ebecedf1f2e9864d45b92d86f85aaaa failed",
                        "/home/runner/_work/_temp/0000aaaa-1111.sh: line 5: python: command not found"));

        assertThat(a).isEqualTo(b).hasSize(16);
    }

    @Test
    void fingerprint_differentErrorOrStepOrRepo_differs() {
        String base = LogExcerpts.fingerprint("evi", "wf", List.of("job → step"), List.of("disk full"));

        assertThat(LogExcerpts.fingerprint("evi", "wf", List.of("job → step"), List.of("permission denied")))
                .isNotEqualTo(base);
        assertThat(LogExcerpts.fingerprint("evi", "wf", List.of("job → other"), List.of("disk full")))
                .isNotEqualTo(base);
        assertThat(LogExcerpts.fingerprint("he-kms", "wf", List.of("job → step"), List.of("disk full")))
                .isNotEqualTo(base);
    }

    @Test
    void fingerprint_ignoresStepOrder() {
        assertThat(LogExcerpts.fingerprint("r", "w", List.of("b", "a"), List.of("x")))
                .isEqualTo(LogExcerpts.fingerprint("r", "w", List.of("a", "b"), List.of("x")));
    }
}
