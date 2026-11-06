package com.jirabot.slack.cialert;

import static com.jirabot.slack.cialert.CiReplyRenderer.escape;

import com.jirabot.slack.cialert.GrypeReport.Finding;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// STUDY: SBOM 취약점 답글 (v0.0.92). 고칠 레포별로 묶어 "취약점 · 패키지 현재→고칠 버전"을 한 줄씩 보여준다.
//        같은 취약점이 여러 레포 이미지에 걸리면 각 레포 아래에 모두 나온다 (각자 고쳐야 하므로).
public final class SbomReplyRenderer {

    static final int MAX_LINES_PER_REPO = 12;
    static final int MAX_UNFIXABLE_LINES = 8;
    private static final Pattern TOKEN = Pattern.compile("\\d+|[^\\d]+");

    private SbomReplyRenderer() {}

    // ownerOf: 이미지 → 고칠 레포
    public static String render(String repo, String workflow, GrypeReport report, Function<String, String> ownerOf,
                                String headSha, String runUrl) {
        return render(repo, workflow, report, ownerOf, headSha, runUrl, false);
    }

    // fixEnabled: 자동 수정(v0.0.94)이 켜져 있으면 "아래 답글에 PR 링크", 꺼져 있으면 직접 고치라는 안내.
    public static String render(String repo, String workflow, GrypeReport report, Function<String, String> ownerOf,
                                String headSha, String runUrl, boolean fixEnabled) {
        List<Finding> fixable = report.findings().stream().filter(Finding::fixable).toList();
        List<Finding> unfixable = report.findings().stream().filter(f -> !f.fixable()).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("🛡️ *SBOM 취약점 분석* · ").append(escape(repo)).append(" · ").append(escape(workflow)).append('\n');
        long vulns = report.findings().stream().map(Finding::vulnId).distinct().count();
        sb.append("High/Critical 취약점 *").append(vulns).append("개* (패키지 기준 ").append(report.findings().size())
                .append("건 — 고칠 버전 있음 ").append(fixable.size()).append(" · 없음 ").append(unfixable.size()).append(")\n");

        for (Map.Entry<String, List<Finding>> group : byOwner(fixable, ownerOf).entrySet()) {
            TreeSet<String> images = new TreeSet<>();
            group.getValue().forEach(f -> f.images().stream()
                    .filter(img -> ownerOf.apply(img).equals(group.getKey())).forEach(images::add));
            sb.append("*").append(escape(group.getKey())).append("* — ").append(escape(String.join(", ", images)))
                    .append('\n');
            appendPackageFixes(sb, group.getValue(), MAX_LINES_PER_REPO);
        }
        if (!unfixable.isEmpty()) {
            sb.append("⚠️ *고칠 버전 없음*\n");
            appendUnfixable(sb, unfixable, MAX_UNFIXABLE_LINES);
        }
        if (!fixable.isEmpty() && fixEnabled) {
            sb.append("_고칠 수 있는 항목은 수정 PR 을 자동으로 만들어요 — 아래 답글에 링크가 달려요._\n");
        } else if (!fixable.isEmpty()) {
            sb.append("_자동 수정이 꺼져 있어요. 위 표대로 직접 고치거나 `/fix-sbom-cve`를 써주세요._\n");
        }
        sb.append("_커밋 ").append(escape(CiFailureAnalyzer.shortSha(headSha))).append(" · <").append(runUrl)
                .append("|run 보기>_");
        return sb.toString();
    }

    // DB 요약(반복 알림 답글에도 쓰인다): "High/Critical 취약점 3개: CVE-a, CVE-b, GHSA-c"
    public static String summary(GrypeReport report) {
        List<String> ids = report.findings().stream().map(Finding::vulnId).distinct().toList();
        String head = String.join(", ", ids.subList(0, Math.min(5, ids.size())));
        return "High/Critical 취약점 " + ids.size() + "개: " + head + (ids.size() > 5 ? " 외 " + (ids.size() - 5) + "개" : "");
    }

    static Map<String, List<Finding>> byOwner(List<Finding> findings, Function<String, String> ownerOf) {
        Map<String, List<Finding>> out = new LinkedHashMap<>();
        for (Finding f : findings) {
            new TreeSet<>(f.images().stream().map(ownerOf).toList())
                    .forEach(owner -> out.computeIfAbsent(owner, k -> new ArrayList<>()).add(f));
        }
        return out;
    }

    // STUDY: 고칠 수 있는 항목은 "패키지를 몇 버전으로 올리면 되나" 기준으로 묶는다 — 고치는 사람에게 필요한 단위.
    //        (패키지, 현재 버전)마다 취약점들의 고칠 버전 중 가장 높은 것 하나를 목표로 보여주고,
    //        목표·취약점이 똑같은 패키지(openssl·libssl3t64 처럼 한 소스에서 나온 deb)는 한 줄로 합친다.
    record PackageFix(List<String> pkgs, String version, String target, String type, List<Finding> vulns) {
        int severityRank() {
            return vulns.stream().mapToInt(f -> "critical".equalsIgnoreCase(f.severity()) ? 0 : 1).min().orElse(1);
        }
    }

    static List<PackageFix> packageFixes(List<Finding> fixable) {
        Map<String, List<Finding>> byPackage = new LinkedHashMap<>();
        fixable.forEach(f -> byPackage.computeIfAbsent(f.pkg() + "|" + f.version(), k -> new ArrayList<>()).add(f));
        Map<String, PackageFix> merged = new LinkedHashMap<>();
        for (List<Finding> same : byPackage.values()) {
            Finding first = same.get(0);
            String target = same.stream().map(Finding::preferredFix).max(VERSION_ORDER).orElse("");
            List<Finding> vulns = same.stream().sorted(GrypeReport.ORDER).toList();
            String ids = String.join(",", vulns.stream().map(Finding::vulnId).toList());
            merged.merge(first.version() + "|" + target + "|" + ids,
                    new PackageFix(List.of(first.pkg()), first.version(), target, first.type(), vulns),
                    (a, b) -> new PackageFix(concat(a.pkgs(), b.pkgs()), a.version(), a.target(), a.type(), a.vulns()));
        }
        return merged.values().stream()
                .sorted(Comparator.comparingInt(PackageFix::severityRank)
                        .thenComparing(pf -> -pf.vulns().size())
                        .thenComparing(pf -> pf.pkgs().get(0)))
                .toList();
    }

    private static void appendPackageFixes(StringBuilder sb, List<Finding> fixable, int max) {
        List<PackageFix> fixes = packageFixes(fixable);
        for (PackageFix pf : fixes.subList(0, Math.min(max, fixes.size()))) {
            sb.append("• ").append(packageNames(pf.pkgs())).append(' ').append(escape(pf.version()))
                    .append(" → *").append(escape(pf.target())).append("* · ").append(severityCounts(pf.vulns()))
                    .append(" — ").append(vulnIds(pf.vulns(), 3));
            if (!pf.type().isBlank()) {
                sb.append(" · ").append(escape(pf.type()));
            }
            sb.append('\n');
        }
        if (fixes.size() > max) {
            sb.append("  … 외 ").append(fixes.size() - max).append("개 패키지 (run 의 grype 아티팩트 참고)\n");
        }
    }

    // 고칠 버전이 없으면 취약점마다 판단이 달라서(예외 후보 / 사람 판단) 취약점 단위로 보여준다.
    private static void appendUnfixable(StringBuilder sb, List<Finding> unfixable, int max) {
        Map<String, List<Finding>> byVuln = new LinkedHashMap<>();
        unfixable.forEach(f -> byVuln.computeIfAbsent(f.vulnId() + "|" + f.version(), k -> new ArrayList<>()).add(f));
        int shown = 0;
        for (List<Finding> same : byVuln.values()) {
            if (shown++ == max) {
                break;
            }
            Finding f = same.get(0);
            sb.append("• ").append(vulnIds(List.of(f), 1)).append(' ').append(capitalize(f.severity())).append(" · ")
                    .append(packageNames(same.stream().map(Finding::pkg).distinct().toList())).append(' ')
                    .append(escape(f.version()));
            if (f.suppressCandidate()) {
                sb.append(" — NVD ").append(f.nvdScore()).append(" → `.grype.yaml` 예외 후보");
            } else {
                sb.append(" — ").append(f.nvdScore() == null ? "NVD 점수 없음" : "NVD " + f.nvdScore())
                        .append(" → 사람 판단 필요 (패키지 제거·베이스 이미지 교체·위험 수용)");
            }
            sb.append('\n');
        }
        if (byVuln.size() > max) {
            sb.append("  … 외 ").append(byVuln.size() - max).append("건\n");
        }
    }

    private static String severityCounts(List<Finding> vulns) {
        long critical = vulns.stream().filter(f -> "critical".equalsIgnoreCase(f.severity())).count();
        long high = vulns.size() - critical;
        StringBuilder sb = new StringBuilder();
        if (critical > 0) {
            sb.append("Critical ").append(critical);
        }
        if (high > 0) {
            sb.append(critical > 0 ? " · " : "").append("High ").append(high);
        }
        return sb.toString();
    }

    // `GHSA-x` (CVE-…) 형식, 최대 max 개 + "외 N개"
    private static String vulnIds(List<Finding> vulns, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, vulns.size()); i++) {
            Finding f = vulns.get(i);
            sb.append(i == 0 ? "" : ", ").append('`').append(escape(f.vulnId())).append('`');
            f.relatedIds().stream().filter(id -> id.startsWith("CVE-")).sorted().findFirst()
                    .filter(cve -> !f.vulnId().startsWith("CVE-"))
                    .ifPresent(cve -> sb.append(" (").append(escape(cve)).append(')'));
        }
        if (vulns.size() > max) {
            sb.append(" 외 ").append(vulns.size() - max).append("개");
        }
        return sb.toString();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        b.stream().filter(x -> !out.contains(x)).forEach(out::add);
        return out;
    }

    // STUDY: 간단한 자연 정렬 비교 — 숫자 덩어리는 수로, 나머지는 문자열로 비교 ("1.26.10" > "1.26.9",
    //        "3.5.5-1ubuntu3.6" > "3.5.5-1ubuntu3.2"). 화면에 "가장 높은 고칠 버전"을 고르는 용도라 이 정도면 충분하다.
    static final Comparator<String> VERSION_ORDER = (x, y) -> {
        Matcher a = TOKEN.matcher(x);
        Matcher b = TOKEN.matcher(y);
        while (true) {
            boolean ha = a.find();
            boolean hb = b.find();
            if (!ha || !hb) {
                return Boolean.compare(ha, hb);
            }
            String ta = a.group();
            String tb = b.group();
            int c = Character.isDigit(ta.charAt(0)) && Character.isDigit(tb.charAt(0))
                    ? new BigInteger(ta).compareTo(new BigInteger(tb))
                    : ta.compareTo(tb);
            if (c != 0) {
                return c;
            }
        }
    };

    private static String packageNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, names.size()); i++) {
            sb.append(i == 0 ? "" : ", ").append('`').append(escape(names.get(i))).append('`');
        }
        if (names.size() > 3) {
            sb.append(" 외 ").append(names.size() - 3).append("개");
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
