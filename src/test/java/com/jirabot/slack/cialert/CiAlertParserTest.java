package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

// 알림 본문은 실제 형식(main-ci-failure-alert.yaml / sbom-failure-alert.yaml)을 따르되 값은 가상이다
// — 이 레포는 공개라서 사내 로그·커밋 메시지를 픽스처로 넣지 않는다.
class CiAlertParserTest {

    private static final List<String> REPOS = List.of("he-kms", "envector", "evi", "heracles");

    static final String CI_ALERT = """
            :rotating_light: *evi main failed on main* (CryptoLabInc/evi)
            run: <https://github.com/CryptoLabInc/evi/actions/runs/41000000001>
            commit: `c1d2b07d` Fix the thing (#1) (dev)
            failed steps:
            test (gpu) → Build and test in Docker
            ```CMake Error: The source directory "/workspace" does not appear to contain CMakeLists.txt.
            ##[error]Process completed with exit code 1.
            ```""";

    static final String SBOM_ALERT = """
            :rotating_light: *SBOM pipeline failed on main*
            run: <https://github.com/CryptoLabInc/envector/actions/runs/41000000002>
            commit: `52e03a49`
            failed steps:
            build-docker-image (linux) → Generate SBOM and Grype JSON
            ```[0151] ERROR discovered vulnerabilities at or above the severity threshold
            ```
            To fix locally: run `/fix-sbom-cve <https://github.com/CryptoLabInc/envector/actions/runs/41000000002>` in Claude Code""";

    @Test
    void ciAlert_parsesRepoRunAndKind() {
        CiAlert a = CiAlertParser.parse(CI_ALERT, "CryptoLabInc", REPOS).orElseThrow();

        assertThat(a.kind()).isEqualTo(CiAlert.Kind.CI);
        assertThat(a.repo()).isEqualTo("evi");
        assertThat(a.runId()).isEqualTo(41000000001L);
        assertThat(a.runUrl()).isEqualTo("https://github.com/CryptoLabInc/evi/actions/runs/41000000001");
    }

    @Test
    void sbomAlert_isSbomKind() {
        CiAlert a = CiAlertParser.parse(SBOM_ALERT, "CryptoLabInc", REPOS).orElseThrow();

        assertThat(a.kind()).isEqualTo(CiAlert.Kind.SBOM);
        assertThat(a.repo()).isEqualTo("envector");
    }

    @Test
    void releaseRunWithUnknownSteps_isSbomKind() {
        String text = ":rotating_light: *release run failed on main (step list unavailable — possible SBOM failure)*\n"
                + "run: <https://github.com/CryptoLabInc/envector/actions/runs/7>";
        assertThat(CiAlertParser.parse(text, "CryptoLabInc", REPOS).orElseThrow().kind())
                .isEqualTo(CiAlert.Kind.SBOM);
    }

    @Test
    void slackLinkWithLabel_isParsed() {
        String text = "*gitbook sync failed on main* run: <https://github.com/CryptoLabInc/heracles/actions/runs/123|run>";
        CiAlert a = CiAlertParser.parse(text, "cryptolabinc", REPOS).orElseThrow();

        assertThat(a.repo()).isEqualTo("heracles");
        assertThat(a.runId()).isEqualTo(123L);
    }

    @Test
    void repoNameCase_isIgnored() {
        // 설정은 he-kms, 실제 레포 이름(알림 URL)은 HE-KMS
        String text = ":rotating_light: *HE-KMS main push failed on main* (CryptoLabInc/HE-KMS)\n"
                + "run: <https://github.com/CryptoLabInc/HE-KMS/actions/runs/55>";
        CiAlert a = CiAlertParser.parse(text, "CryptoLabInc", REPOS).orElseThrow();

        assertThat(a.repo()).isEqualTo("HE-KMS");
        assertThat(a.runUrl()).isEqualTo("https://github.com/CryptoLabInc/HE-KMS/actions/runs/55");
    }

    @Test
    void repoOutsideAllowList_isIgnored() {
        String text = "run: <https://github.com/CryptoLabInc/other-repo/actions/runs/1>";
        assertThat(CiAlertParser.parse(text, "CryptoLabInc", REPOS)).isEmpty();
    }

    @Test
    void otherOrg_isIgnored() {
        String text = "run: <https://github.com/someone/evi/actions/runs/1>";
        assertThat(CiAlertParser.parse(text, "CryptoLabInc", REPOS)).isEmpty();
    }

    @Test
    void messageWithoutRunLink_isIgnored() {
        assertThat(CiAlertParser.parse("요새 취약점 쏟아지나요?", "CryptoLabInc", REPOS)).isEmpty();
        assertThat(CiAlertParser.parse(null, "CryptoLabInc", REPOS)).isEmpty();
        assertThat(CiAlertParser.parse("https://github.com/CryptoLabInc/evi/pull/3", "CryptoLabInc", REPOS)).isEmpty();
    }
}
