package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.cialert.GrypeReport.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SbomReplyRendererTest {

    private static final String RUN = "https://github.com/CryptoLabInc/envector/actions/runs/1";
    private static final CiAlertProperties PROPS = new CiAlertProperties(true, "C", List.of("B"), "CryptoLabInc",
            List.of("envector"), "t", null, 24, 180, 60,
            List.of("envector-kms-control=HE-KMS", " envector-kms-crypto = HE-KMS "));
    private static final Function<String, String> OWNER = img -> PROPS.ownerOf(img, "envector");

    private static Finding f(String id, String sev, String pkg, String ver, List<String> fix, String state,
                             Double nvd, Set<String> related, String... images) {
        return new Finding(id, sev, pkg, ver, "go-module", fix, state, nvd, related,
                new TreeSet<>(List.of(images)), new TreeSet<>(List.of("amd64")));
    }

    @Test
    void ownerOf_usesMappingThenFallback() {
        assertThat(PROPS.ownerOf("envector-kms-crypto", "envector")).isEqualTo("HE-KMS");
        assertThat(PROPS.ownerOf("envector-backend", "envector")).isEqualTo("envector");
    }

    @Test
    void groupsByOwningRepo_andShowsFixVersions() {
        GrypeReport r = new GrypeReport(List.of(
                f("GHSA-x", "Critical", "github.com/acme/jose", "v4.1.5", List.of("v4.1.6", "v5.0.1"), "fixed", 9.1,
                        Set.of("CVE-2026-1"), "envector-kms-crypto", "envector-backend"),
                f("CVE-2026-2", "High", "stdlib", "go1.26.8", List.of("1.26.9"), "fixed", null, Set.of(), "envector-backend")), 4);

        String text = SbomReplyRenderer.render("envector", "Release", r, OWNER, "feedbeef99", RUN);

        assertThat(text).startsWith("🛡️ *SBOM 취약점 분석* · envector · Release\n")
                .contains("High/Critical 취약점 *2개* (패키지 기준 2건 — 고칠 버전 있음 2 · 없음 0)")
                .contains("*HE-KMS* — envector-kms-crypto\n• `github.com/acme/jose` v4.1.5 → *v4.1.6* · Critical 1 — `GHSA-x` (CVE-2026-1) · go-module\n")
                .contains("*envector* — envector-backend\n")
                .contains("• `stdlib` go1.26.8 → *1.26.9* · High 1 — `CVE-2026-2` · go-module")
                .doesNotContain("고칠 버전 없음*")
                .contains("자동 수정이 꺼져 있어요")
                .endsWith("_커밋 feedbeef · <" + RUN + "|run 보기>_");
        // 두 레포 이미지에 걸친 취약점은 두 레포 아래에 모두 나온다
        assertThat(text.split("GHSA-x", -1)).hasSize(3);
    }

    @Test
    void unfixable_showsDecisionFromSkillTree() {
        GrypeReport r = new GrypeReport(List.of(
                f("CVE-9", "High", "libfoo", "1.0", List.of(), "not-fixed", 5.0, Set.of(), "envector-backend"),
                f("CVE-8", "Critical", "libbar", "2.0", List.of(), "not-fixed", 8.1, Set.of(), "envector-backend"),
                f("CVE-7", "High", "libbaz", "3.0", List.of(), "unknown", null, Set.of(), "envector-backend")), 1);

        String text = SbomReplyRenderer.render("envector", "Release", r, OWNER, "abc", RUN);

        assertThat(text).contains("⚠️ *고칠 버전 없음*")
                .contains("`CVE-9` High · `libfoo` 1.0 — NVD 5.0 → `.grype.yaml` 예외 후보")
                .contains("`CVE-8` Critical · `libbar` 2.0 — NVD 8.1 → 사람 판단 필요")
                .contains("`CVE-7` High · `libbaz` 3.0 — NVD 점수 없음 → 사람 판단 필요");
    }

    @Test
    void longLists_areCutWithRemainderCount_andTextEscaped() {
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(f("CVE-" + i, "High", "pkg<" + i + ">", "1", List.of("2"), "fixed", null, Set.of(), "envector-backend"));
        }

        String text = SbomReplyRenderer.render("envector", "R", new GrypeReport(many, 1), OWNER, "abc", RUN);

        assertThat(text).contains("… 외 3개 패키지").contains("`pkg&lt;0&gt;`").doesNotContain("pkg<0>");
    }

    @Test
    void sameVulnerabilityAcrossBinaryPackages_isOneLine_andFixMatchesInstalledMajor() {
        List<Finding> findings = new ArrayList<>();
        for (String pkg : List.of("openssl", "libssl3t64", "openssl-provider-legacy", "libssl-dev")) {
            findings.add(new Finding("CVE-2026-45447", "High", pkg, "3.5.5-1ubuntu3", "deb", List.of("3.5.5-1ubuntu3.2"),
                    "fixed", null, Set.of(), new TreeSet<>(List.of("envector-backend")), new TreeSet<>(List.of("amd64"))));
        }
        findings.add(new Finding("CVE-2026-41254", "High", "openjdk", "17.0.19+10", "binary",
                List.of("1.8.0_502", "8.0.502", "11.0.32", "17.0.20", "21.0.12"), "fixed", null, Set.of(),
                new TreeSet<>(List.of("envector-backend")), new TreeSet<>(List.of("amd64"))));

        String text = SbomReplyRenderer.render("envector", "R", new GrypeReport(findings, 1), OWNER, "abc", RUN);

        assertThat(text).contains("• `openssl`, `libssl3t64`, `openssl-provider-legacy` 외 1개 "
                        + "3.5.5-1ubuntu3 → *3.5.5-1ubuntu3.2* · High 1 — `CVE-2026-45447` · deb\n")
                .contains("• `openjdk` 17.0.19+10 → *17.0.20* · High 1 — `CVE-2026-41254` · binary")
                .doesNotContain("1.8.0_502");
        assertThat(text.split("CVE-2026-45447", -1)).hasSize(2);   // 한 줄
    }

    @Test
    void preferredFix_prefersSameMajorMinor_thenSameMajor_thenFirst() {
        Finding goStd = f("GO-1", "Critical", "stdlib", "go1.26.2", List.of("1.25.11", "1.26.9"), "fixed", null, Set.of(), "a");
        Finding xnet = f("GO-2", "High", "golang.org/x/net", "v0.40.0", List.of("0.55.0"), "fixed", null, Set.of(), "a");
        Finding other = f("CVE-1", "High", "x", "3.0", List.of("4.1", "5.0"), "fixed", null, Set.of(), "a");

        assertThat(goStd.preferredFix()).isEqualTo("1.26.9");    // 메이저만 보면 더 낮은 1.25.11 을 고른다
        assertThat(xnet.preferredFix()).isEqualTo("0.55.0");
        assertThat(other.preferredFix()).isEqualTo("4.1");
        assertThat(GrypeReport.numericParts("go1.26.2")).containsExactly(1, 26, 2);
        assertThat(GrypeReport.numericParts("17.0.19+10")).containsExactly(17, 0, 19);
        assertThat(GrypeReport.numericParts("3.5.5-1ubuntu3")).containsExactly(3, 5, 5);
        assertThat(GrypeReport.numericParts("abc")).isEmpty();
    }

    @Test
    void severalVulnsOnOnePackage_collapseToHighestTarget() {
        GrypeReport r = new GrypeReport(List.of(
                f("GO-1", "Critical", "stdlib", "go1.26.2", List.of("1.25.11", "1.26.9"), "fixed", null, Set.of("CVE-1"), "envector-backend"),
                f("GO-2", "High", "stdlib", "go1.26.2", List.of("1.26.3"), "fixed", null, Set.of(), "envector-backend"),
                f("GO-3", "High", "stdlib", "go1.26.2", List.of("1.26.10"), "fixed", null, Set.of(), "envector-backend"),
                f("GO-4", "High", "stdlib", "go1.26.2", List.of("1.26.4"), "fixed", null, Set.of(), "envector-backend")), 1);

        String text = SbomReplyRenderer.render("envector", "R", r, OWNER, "abc", RUN);

        assertThat(text).contains("• `stdlib` go1.26.2 → *1.26.10* · Critical 1 · High 3 — `GO-1` (CVE-1), `GO-2`, `GO-3` 외 1개 · go-module\n");
        assertThat(text.split("`stdlib`", -1)).hasSize(2);
    }

    @Test
    void versionOrder_isNatural() {
        assertThat(SbomReplyRenderer.VERSION_ORDER.compare("1.26.10", "1.26.9")).isPositive();
        assertThat(SbomReplyRenderer.VERSION_ORDER.compare("3.5.5-1ubuntu3.6", "3.5.5-1ubuntu3.2")).isPositive();
        assertThat(SbomReplyRenderer.VERSION_ORDER.compare("0.55.0", "0.55.0")).isZero();
        assertThat(SbomReplyRenderer.VERSION_ORDER.compare("1.2", "1.2.1")).isNegative();
    }

    @Test
    void summary_listsFirstFiveIds() {
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(f("CVE-" + i, "High", "p" + i, "1", List.of("2"), "fixed", null, Set.of(), "a"));
        }

        assertThat(SbomReplyRenderer.summary(new GrypeReport(many, 1)))
                .isEqualTo("High/Critical 취약점 7개: CVE-0, CVE-1, CVE-2, CVE-3, CVE-4 외 2개");
    }
}
