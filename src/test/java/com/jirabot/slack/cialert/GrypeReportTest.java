package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GrypeReport.Finding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

// grype -o json 의 matches 구조를 따른 가상 데이터 (실제 스캔 결과는 공개 레포에 넣지 않는다).
class GrypeReportTest {

    private final ObjectMapper om = new ObjectMapper();

    static String match(String id, String severity, String pkg, String version, String type, String fixState,
                        String fixVersions, String cvss, String related) {
        return """
                {"vulnerability":{"id":"%s","severity":"%s","fix":{"versions":[%s],"state":"%s"},"cvss":[%s]},
                 "relatedVulnerabilities":[%s],
                 "artifact":{"name":"%s","version":"%s","type":"%s"}}""".formatted(
                id, severity, fixVersions, fixState, cvss, related, pkg, version, type);
    }

    static byte[] grype(String... matches) {
        return ("{\"matches\":[" + String.join(",", matches) + "],\"ignoredMatches\":[]}").getBytes(StandardCharsets.UTF_8);
    }

    static final String NVD_9 = "{\"source\":\"nvd@nist.gov\",\"metrics\":{\"baseScore\":9.1}}";
    static final String GHSA_ONLY = "{\"source\":\"security-advisories@github.com\",\"metrics\":{\"baseScore\":8.8}}";

    @Test
    void keepsOnlyHighAndCritical_mergesAcrossImagesAndArchs() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("envector-kms-crypto-main-amd64-grype.json", grype(
                match("GHSA-aaaa-bbbb-cccc", "High", "github.com/acme/jose", "v4.1.5", "go-module", "fixed",
                        "\"v4.1.6\"", GHSA_ONLY,
                        "{\"id\":\"CVE-2026-0001\",\"namespace\":\"nvd:cpe\",\"cvss\":[" + NVD_9 + "]}"),
                match("CVE-2026-0009", "Medium", "zlib", "1.2", "deb", "fixed", "\"1.3\"", "", "")));
        files.put("envector-kms-crypto-main-arm64-grype.json", grype(
                match("GHSA-aaaa-bbbb-cccc", "High", "github.com/acme/jose", "v4.1.5", "go-module", "fixed",
                        "\"v4.1.6\"", GHSA_ONLY, "")));
        files.put("envector-compute-main-amd64-gpu-grype.json", grype(
                match("CVE-2026-0002", "Critical", "libssl3", "3.0.2-0ubuntu1.18", "deb", "fixed",
                        "\"3.0.2-0ubuntu1.19\"", NVD_9, "")));

        GrypeReport r = GrypeReport.parse(files, om);

        assertThat(r.scannedFiles()).isEqualTo(3);
        assertThat(r.findings()).extracting(Finding::vulnId)
                .containsExactly("CVE-2026-0002", "GHSA-aaaa-bbbb-cccc");   // Critical 먼저, Medium 제외
        Finding jose = r.findings().get(1);
        assertThat(jose.images()).containsExactly("envector-kms-crypto");
        assertThat(jose.archs()).containsExactly("amd64", "arm64");
        assertThat(jose.relatedIds()).containsExactly("CVE-2026-0001");
        assertThat(jose.nvdScore()).isEqualTo(9.1);                        // GHSA 자체 점수가 아니라 NVD 점수
        assertThat(jose.fixable()).isTrue();
        assertThat(r.findings().get(0).archs()).containsExactly("amd64-gpu");
    }

    @Test
    void notFixed_classifiedByNvdScore() throws Exception {
        GrypeReport r = GrypeReport.parse(Map.of("envector-backend-main-amd64-grype.json", grype(
                match("CVE-1", "High", "a", "1", "deb", "not-fixed", "", "{\"source\":\"nvd@nist.gov\",\"metrics\":{\"baseScore\":5.3}}", ""),
                match("CVE-2", "High", "b", "1", "deb", "wont-fix", "", NVD_9, ""),
                match("CVE-3", "Critical", "c", "1", "binary", "unknown", "", "", ""))), om);

        Finding low = r.findings().stream().filter(f -> f.vulnId().equals("CVE-1")).findFirst().orElseThrow();
        Finding high = r.findings().stream().filter(f -> f.vulnId().equals("CVE-2")).findFirst().orElseThrow();
        Finding none = r.findings().stream().filter(f -> f.vulnId().equals("CVE-3")).findFirst().orElseThrow();
        assertThat(low.fixable()).isFalse();
        assertThat(low.suppressCandidate()).isTrue();
        assertThat(high.suppressCandidate()).isFalse();
        assertThat(none.nvdScore()).isNull();
        assertThat(none.suppressCandidate()).isFalse();
    }

    @Test
    void cnaScoresInRelated_areNotTreatedAsNvd() throws Exception {
        // 실제 grype 출력 형태: vulnerability.cvss 는 비어 있고, related(nvd:cpe)에 NVD·CNA 점수가 섞여 있다.
        String related = "{\"id\":\"CVE-5\",\"namespace\":\"nvd:cpe\",\"cvss\":["
                + "{\"source\":\"0b0ca135-cna\",\"metrics\":{\"baseScore\":8.8}},"
                + "{\"source\":\"nvd@nist.gov\",\"metrics\":{\"baseScore\":6.5}}]}";
        String cnaOnly = "{\"id\":\"CVE-6\",\"namespace\":\"nvd:cpe\",\"cvss\":["
                + "{\"source\":\"cve-coordination@google.com\",\"metrics\":{\"baseScore\":9.2}}]}";
        GrypeReport r = GrypeReport.parse(Map.of("envector-backend-main-amd64-grype.json", grype(
                match("CVE-5", "High", "a", "1", "deb", "not-fixed", "", "", related),
                match("CVE-6", "High", "b", "1", "deb", "not-fixed", "", "", cnaOnly))), om);

        Finding withNvd = r.findings().stream().filter(f -> f.vulnId().equals("CVE-5")).findFirst().orElseThrow();
        Finding cna = r.findings().stream().filter(f -> f.vulnId().equals("CVE-6")).findFirst().orElseThrow();
        assertThat(withNvd.nvdScore()).isEqualTo(6.5);
        assertThat(withNvd.relatedIds()).isEmpty();          // 자기 자신은 related 로 치지 않는다
        assertThat(withNvd.suppressCandidate()).isTrue();
        assertThat(cna.nvdScore()).isNull();
        assertThat(cna.suppressCandidate()).isFalse();
    }

    @Test
    void emptyMatches_isEmptyReport() throws Exception {
        GrypeReport r = GrypeReport.parse(Map.of("x-main-amd64-grype.json", grype()), om);

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.scannedFiles()).isEqualTo(1);
    }

    @Test
    void fromZips_readsOnlyGrypeJsonEntries() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("envector-audit-main-arm64-grype.json"));
            zip.write(grype(match("CVE-7", "High", "p", "1", "go-module", "fixed", "\"2\"", "", "")));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("envector-audit-main-arm64-sbom.json"));
            zip.write("{\"not\":\"grype\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        GrypeReport r = GrypeReport.fromZips(List.of(bytes.toByteArray()), om);

        assertThat(r.scannedFiles()).isEqualTo(1);
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.images()).containsExactly("envector-audit");
            assertThat(f.archs()).containsExactly("arm64");
        });
    }

    @Test
    void imageAndArch_fromFileNames() {
        assertThat(GrypeReport.imageAndArch("dir/envector-kms-control-1.6.0-amd64-grype.json"))
                .containsExactly("envector-kms-control", "amd64");
        assertThat(GrypeReport.imageAndArch("0:envector-compute-main-amd64-gpu-grype.json"))
                .containsExactly("envector-compute", "amd64-gpu");
        assertThat(GrypeReport.imageAndArch("weird-grype.json")).containsExactly("weird", "?");
    }
}
