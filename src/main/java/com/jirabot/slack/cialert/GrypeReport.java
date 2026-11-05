package com.jirabot.slack.cialert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// STUDY: envector release.yaml 이 올리는 grype JSON(아티팩트 grype-linux / grype-macos)에서
//        CI 게이트를 실패시킨 High/Critical 취약점만 뽑는다 (v0.0.92). matches 는 .grype.yaml 로
//        무시된 항목(ignoredMatches)을 이미 뺀 목록이라 CI 의 --fail-on high 판단과 같다.
//        같은 (취약점, 패키지, 버전)이 여러 이미지·아키텍처에서 나오면 한 줄로 합친다.
public record GrypeReport(List<Finding> findings, int scannedFiles) {

    private static final Set<String> GATING = Set.of("high", "critical");
    // envector-kms-crypto-main-amd64(-gpu)-grype.json → 이미지 envector-kms-crypto, 아키텍처 amd64(-gpu)
    private static final Pattern FILE_NAME = Pattern.compile("^(.+)-[^-]+-(amd64|arm64)(-gpu)?$");
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    private static final Pattern LEADING_NUMBERS = Pattern.compile("^(\\d+(?:\\.\\d+)*)");

    public record Finding(String vulnId, String severity, String pkg, String version, String type,
                          List<String> fixVersions, String fixState, Double nvdScore,
                          Set<String> relatedIds, Set<String> images, Set<String> archs) {

        public boolean fixable() {
            return "fixed".equalsIgnoreCase(fixState) && !fixVersions.isEmpty();
        }

        // fix-sbom-cve 스킬의 결정 트리: 고칠 버전이 없고 NVD < 7 이면 .grype.yaml 예외 후보, 아니면 사람이 판단.
        public boolean suppressCandidate() {
            return !fixable() && nvdScore != null && nvdScore < 7.0;
        }

        public String key() {
            return vulnId + "|" + pkg + "|" + version;
        }

        // STUDY: grype 는 여러 버전 줄의 고친 버전을 함께 준다 (openjdk 17.0.19 → 1.8.0_502, 11.0.32, 17.0.20 …,
        //        Go stdlib go1.26.2 → 1.25.11, 1.26.9). 설치된 버전과 메이저.마이너가 같은 것 → 메이저가 같은 것 →
        //        첫 값 순으로 고른다. 메이저만 보면 Go(항상 1.x)는 더 낮은 1.25.11 을 고르게 된다.
        public String preferredFix() {
            if (fixVersions.isEmpty()) {
                return "";
            }
            List<Integer> current = numericParts(version);
            for (int depth = Math.min(2, current.size()); depth >= 1; depth--) {
                List<Integer> prefix = current.subList(0, depth);
                for (String v : fixVersions) {
                    List<Integer> p = numericParts(v);
                    if (p.size() >= depth && p.subList(0, depth).equals(prefix)) {
                        return v;
                    }
                }
            }
            return fixVersions.get(0);
        }

        int severityRank() {
            return "critical".equalsIgnoreCase(severity) ? 0 : 1;
        }
    }

    public static final Comparator<Finding> ORDER = Comparator.comparingInt(Finding::severityRank)
            .thenComparing(f -> !f.fixable())
            .thenComparing(Finding::pkg)
            .thenComparing(Finding::vulnId);

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    // 아티팩트 zip 여러 개를 풀어 *-grype.json 만 읽는다.
    public static GrypeReport fromZips(List<byte[]> zips, ObjectMapper om) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (byte[] zip : zips) {
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (!e.isDirectory() && e.getName().endsWith("-grype.json")) {
                        byte[] data = in.readNBytes(MAX_ENTRY_BYTES + 1);
                        if (data.length <= MAX_ENTRY_BYTES) {
                            files.put(files.size() + ":" + e.getName(), data);
                        }
                    }
                }
            }
        }
        return parse(files, om);
    }

    // key = 파일 이름(경로 포함 가능), value = grype JSON
    public static GrypeReport parse(Map<String, byte[]> files, ObjectMapper om) throws IOException {
        Map<String, Builder> merged = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String[] imageArch = imageAndArch(file.getKey());
            JsonNode root = om.readTree(file.getValue());
            for (JsonNode m : root.path("matches")) {
                JsonNode v = m.path("vulnerability");
                String severity = v.path("severity").asText("");
                if (!GATING.contains(severity.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                JsonNode a = m.path("artifact");
                String id = v.path("id").asText("");
                String pkg = a.path("name").asText("");
                String version = a.path("version").asText("");
                Builder b = merged.computeIfAbsent(id + "|" + pkg + "|" + version, k -> new Builder(id, severity, pkg,
                        version, a.path("type").asText(""), v.path("fix")));
                b.images.add(imageArch[0]);
                b.archs.add(imageArch[1]);
                b.nvd = max(b.nvd, nvdScore(v.path("cvss")));
                for (JsonNode r : m.path("relatedVulnerabilities")) {
                    String rid = r.path("id").asText("");
                    if (!rid.isBlank() && !rid.equals(id)) {
                        b.related.add(rid);
                    }
                    // STUDY: 실제 grype 출력에서 NVD 점수는 주로 relatedVulnerabilities(namespace nvd:cpe)의 cvss 에 있고,
                    //        같은 목록에 CNA(제보 기관) 점수도 섞여 있다. 출처가 NVD 인 점수만 쓴다 —
                    //        CNA 점수를 NVD 로 치면 ".grype.yaml 예외 후보" 판단(NVD < 7)이 틀어질 수 있다.
                    b.nvd = max(b.nvd, nvdScore(r.path("cvss")));
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        merged.values().forEach(b -> out.add(b.build()));
        out.sort(ORDER);
        return new GrypeReport(List.copyOf(out), files.size());
    }

    // 버전 앞쪽의 숫자 구간: "go1.26.2" → [1, 26, 2], "v4.1.5" → [4, 1, 5], "17.0.19+10" → [17, 0, 19],
    // "3.5.5-1ubuntu3" → [3, 5, 5]. 숫자로 시작하지 않으면 빈 목록.
    static List<Integer> numericParts(String version) {
        Matcher m = LEADING_NUMBERS.matcher(version == null ? "" : version.replaceFirst("^(go|v)", ""));
        if (!m.find()) {
            return List.of();
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : m.group(1).split("\\.")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                break;   // 너무 긴 숫자는 비교에서 뺀다
            }
        }
        return parts;
    }

    static String[] imageAndArch(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("^\\d+:", "")
                .replaceFirst("-grype\\.json$", "");
        Matcher m = FILE_NAME.matcher(name);
        if (!m.matches()) {
            return new String[] {name, "?"};
        }
        return new String[] {m.group(1), m.group(2) + (m.group(3) == null ? "" : m.group(3))};
    }

    // cvss 항목 중 출처가 NVD(nvd@nist.gov)인 것의 base score 최댓값. 없으면 null.
    private static Double nvdScore(JsonNode cvssList) {
        Double best = null;
        for (JsonNode c : cvssList) {
            String source = c.path("source").asText("").toLowerCase(Locale.ROOT);
            JsonNode score = c.path("metrics").path("baseScore");
            if (source.contains("nvd") && score.isNumber()) {
                best = max(best, score.asDouble());
            }
        }
        return best;
    }

    private static Double max(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }

    private static final class Builder {
        final String id;
        final String severity;
        final String pkg;
        final String version;
        final String type;
        final List<String> fixVersions = new ArrayList<>();
        final String fixState;
        final Set<String> related = new TreeSet<>();
        final Set<String> images = new TreeSet<>();
        final Set<String> archs = new TreeSet<>();
        Double nvd;

        Builder(String id, String severity, String pkg, String version, String type, JsonNode fix) {
            this.id = id;
            this.severity = severity;
            this.pkg = pkg;
            this.version = version;
            this.type = type;
            fix.path("versions").forEach(x -> fixVersions.add(x.asText()));
            this.fixState = fix.path("state").asText("");
        }

        Finding build() {
            return new Finding(id, severity, pkg, version, type, List.copyOf(fixVersions), fixState, nvd,
                    Set.copyOf(related), new TreeSet<>(images), new TreeSet<>(archs));
        }
    }
}
