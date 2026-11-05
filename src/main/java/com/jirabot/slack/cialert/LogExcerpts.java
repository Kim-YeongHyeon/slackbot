package com.jirabot.slack.cialert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

// STUDY: CI 로그를 Claude 에 넣을 만큼만 잘라내고(excerpt), 반복 알림을 알아보기 위한 지문(fingerprint)을 만든다.
//        둘 다 순수 함수라 실제 로그 픽스처로 단위 테스트한다.
public final class LogExcerpts {

    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*[A-Za-z]");
    // GitHub Actions 로그 줄 앞의 타임스탬프 "2026-11-05T05:08:29.2422032Z "
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ?");
    private static final String ERROR_MARK = "##[error]";
    private static final int CONTEXT_BEFORE = 20;
    private static final int TAIL_LINES = 60;
    private static final int MAX_LINE = 400;
    private static final int FINGERPRINT_LINES = 5;

    // STUDY: 지문 정규화 — 실행마다 달라지는 값(경로의 임시 디렉터리, 해시, 숫자)을 지워서
    //        "같은 원인"이면 같은 문자열이 되게 한다. 순서가 중요하다: 경로 → 16진수 → 숫자.
    private static final Pattern TEMP_PATH = Pattern.compile("(/_temp/|/tmp/)[^\\s'\"]+");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{7,}\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private LogExcerpts() {}

    public static List<String> cleanLines(String rawLog) {
        List<String> out = new ArrayList<>();
        // 빈 로그(다운로드 실패)를 빈 줄 1개로 만들면 "(로그를 받지 못함)" 대신 빈 발췌가 Claude 에 간다.
        if (rawLog == null || rawLog.isBlank()) {
            return out;
        }
        for (String line : rawLog.split("\r?\n")) {
            String l = TIMESTAMP.matcher(ANSI.matcher(line).replaceAll("")).replaceFirst("");
            out.add(l.length() > MAX_LINE ? l.substring(0, MAX_LINE) + "…" : l);
        }
        return out;
    }

    // ##[error] 앞 20줄 + 마지막 60줄. 겹치는 줄은 한 번만, 원래 순서대로. maxChars 를 넘으면 뒤쪽을 남긴다.
    public static String excerpt(List<String> lines, int maxChars) {
        TreeSet<Integer> keep = new TreeSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(ERROR_MARK)) {
                for (int k = Math.max(0, i - CONTEXT_BEFORE); k <= i; k++) {
                    keep.add(k);
                }
            }
        }
        for (int k = Math.max(0, lines.size() - TAIL_LINES); k < lines.size(); k++) {
            keep.add(k);
        }
        StringBuilder sb = new StringBuilder();
        int prev = -2;
        for (int idx : keep) {
            if (prev >= 0 && idx != prev + 1) {
                sb.append("…\n");
            }
            sb.append(lines.get(idx)).append('\n');
            prev = idx;
        }
        String s = sb.toString();
        return s.length() > maxChars ? "…" + s.substring(s.length() - maxChars) : s;
    }

    // 지문에 쓰는 "에러 직전 줄들": 첫 ##[error] 앞의 의미 있는 줄 5개. ##[error] 가 없으면 마지막 5줄.
    // "Process completed with exit code 1" 같은 공통 문구보다 바로 앞 줄이 실제 원인을 담는다.
    public static List<String> errorContext(List<String> lines) {
        int first = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(ERROR_MARK)) {
                first = i;
                break;
            }
        }
        int end = first >= 0 ? first : lines.size();
        List<String> picked = new ArrayList<>();
        for (int i = end - 1; i >= 0 && picked.size() < FINGERPRINT_LINES; i--) {
            String l = lines.get(i).strip();
            if (!l.isEmpty() && !l.startsWith("##[group]") && !l.startsWith("##[endgroup]")) {
                picked.add(0, l);
            }
        }
        if (first >= 0) {
            picked.add(lines.get(first).strip());
        }
        return picked;
    }

    public static String normalize(String line) {
        String s = line.toLowerCase();
        s = TEMP_PATH.matcher(s).replaceAll("$1X");
        s = HEX.matcher(s).replaceAll("H");
        s = DIGITS.matcher(s).replaceAll("N");
        return SPACES.matcher(s).replaceAll(" ").strip();
    }

    // repo + 워크플로 + 실패 step(정렬) + 정규화한 에러 줄 → SHA-256 앞 16자.
    public static String fingerprint(String repo, String workflow, Collection<String> failedSteps,
                                     Collection<String> errorLines) {
        StringBuilder sb = new StringBuilder(repo).append('\n').append(workflow).append('\n');
        new TreeSet<>(failedSteps).forEach(s -> sb.append(s).append('\n'));
        errorLines.stream().map(LogExcerpts::normalize).sorted().forEach(l -> sb.append(l).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
