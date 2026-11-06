package com.jirabot.slack.cialert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GrypeReport.Finding;
import com.jirabot.slack.client.process.ProcessRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

// STUDY: 에이전트가 고친 결과를 Java 가 결정적으로 확인한다 (v0.0.94).
//        - go-module: 대상 패키지를 require 하는 go.mod 디렉터리마다 grype dir: 로 다시 검사 → 그 패키지의
//          High/Critical 이 남았으면 실패(남은 항목은 재시도 입력). 버전 계열이 달라 표의 버전이 부족한 경우(grpc 1.83.1)도 여기서 잡힌다.
//        - stdlib: Dockerfile 의 Go 고정값이 고칠 버전 이상인지, sha256 이 go.dev 공식 값과 같은지 대조.
//        - deb 등: 이미지 빌드 없이는 확인할 수 없어 PR 의 CI 에 맡긴다.
@Component
public class FixVerifier {

    private static final Pattern GO_PIN = Pattern.compile("(?m)^\\s*ARG\\s+GO_VER(?:SION)?=(\\S+)\\s*$");
    private static final Pattern GO_SHA = Pattern.compile("(?m)^\\s*ARG\\s+GO_SHA256(?:_(ARM64|AMD64|X86_64))?=([0-9a-f]{64})\\s*$");
    private static final Set<String> GATING = Set.of("high", "critical");

    private final ProcessRunner runner;
    private final CiFixProperties fixProps;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FixVerifier(ProcessRunner runner, CiFixProperties fixProps, ObjectMapper objectMapper) {
        this.runner = runner;
        this.fixProps = fixProps;
        this.objectMapper = objectMapper;
    }

    public record Check(boolean ok, List<String> remaining, List<String> lines) {}

    public Check verify(FixWorkspace.Workspace ws, List<Finding> targets) {
        List<String> remaining = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Set<String> goPkgs = new LinkedHashSet<>();
        targets.stream().filter(f -> "go-module".equals(f.type())).forEach(f -> goPkgs.add(f.pkg()));
        if (!goPkgs.isEmpty()) {
            for (Path dir : goModDirsRequiring(ws.agentDir(), goPkgs)) {
                String rel = ws.agentDir().relativize(dir).toString();
                List<String> left = gatingFor(grype(ws, dir), goPkgs);
                lines.add("grype `" + (rel.isEmpty() ? "." : rel) + "`: 대상 패키지 High/Critical " + left.size() + "건");
                left.forEach(l -> remaining.add(l + " (" + (rel.isEmpty() ? "." : rel) + ")"));
            }
        }
        targets.stream().filter(f -> "stdlib".equals(f.type())).findFirst().ifPresent(f -> {
            List<String> problems = checkGoPins(ws.agentDir(), f.preferredFix());
            lines.add(problems.isEmpty() ? "Go 고정값: " + f.preferredFix() + " 이상, sha256 go.dev 와 일치" : "Go 고정값 문제 " + problems.size() + "건");
            remaining.addAll(problems);
        });
        targets.stream().map(Finding::type).filter(t -> !"go-module".equals(t) && !"stdlib".equals(t)).distinct()
                .forEach(t -> lines.add(t + " 항목은 이미지 빌드가 필요해 이 PR 의 CI 가 확인"));
        return new Check(remaining.isEmpty(), remaining, lines);
    }

    // 대상 패키지 이름이 들어 있는 go.mod 의 디렉터리 (서브모듈·vendor 제외).
    static List<Path> goModDirsRequiring(Path root, Set<String> pkgs) {
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> out = new ArrayList<>();
            for (Path gomod : s.filter(p -> p.getFileName().toString().equals("go.mod")).toList()) {
                String rel = root.relativize(gomod).toString();
                if (rel.matches("(?:.*/)?(?:vendor|node_modules|external)/.*")) {
                    continue;
                }
                String text = Files.readString(gomod);
                if (pkgs.stream().anyMatch(p -> Pattern.compile("(?m)^\\s*(?:require\\s+)?" + Pattern.quote(p) + "\\s+v").matcher(text).find())) {
                    out.add(gomod.getParent());
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> gatingFor(JsonNode report, Set<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (JsonNode m : report.path("matches")) {
            String sev = m.path("vulnerability").path("severity").asText("").toLowerCase(Locale.ROOT);
            String pkg = m.path("artifact").path("name").asText("");
            if (GATING.contains(sev) && pkgs.contains(pkg)) {
                JsonNode fix = m.path("vulnerability").path("fix").path("versions");
                out.add(m.path("vulnerability").path("id").asText() + " " + pkg + " " + m.path("artifact").path("version").asText()
                        + (fix.isArray() && !fix.isEmpty() ? " → " + fix.get(0).asText() : ""));
            }
        }
        return out;
    }

    private JsonNode grype(FixWorkspace.Workspace ws, Path dir) {
        Path tmp = ws.root().resolve("tmp");
        try {
            Files.createDirectories(tmp);
            Files.createDirectories(Path.of(fixProps.grypeCacheDir()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ProcessRunner.Result id = runner.run(List.of("id", "-u"), null, Duration.ofSeconds(5));
        String uid = id.stdout() == null || id.stdout().isBlank() ? "1000" : id.stdout().strip();
        ProcessRunner.Result r = runner.run(List.of("sudo", "-n", "docker", "run", "--rm", "--user", uid + ":" + uid,
                "-e", "HOME=" + tmp, "-e", "TMPDIR=" + tmp, "-e", "GRYPE_DB_CACHE_DIR=/grype-db",
                "-v", fixProps.grypeCacheDir() + ":/grype-db", "-v", ws.root() + ":" + ws.root(),
                fixProps.grypeImage(), "dir:" + dir, "-o", "json"), null, Duration.ofMinutes(15));
        try {
            if (r.stdout() == null || r.stdout().isBlank()) {
                throw new IllegalStateException("grype 재검사 실패: " + (r.stderr() == null ? "" : r.stderr().strip()));
            }
            return objectMapper.readTree(r.stdout());
        } catch (IOException e) {
            throw new IllegalStateException("grype 결과를 읽지 못했어요: " + e.getMessage(), e);
        }
    }

    // Dockerfile 의 ARG GO_VERSION/GO_VER 가 모두 target 이상이고, 같은 파일의 GO_SHA256* 이 go.dev 값과 같은지.
    List<String> checkGoPins(Path root, String target) {
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, String>> official = new HashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("Dockerfile.*|.*\\.Dockerfile")).toList()) {
                String rel = root.relativize(f).toString();
                if (rel.matches("(?:.*/)?(?:external|vendor|node_modules)/.*")) {
                    continue;
                }
                String text = Files.readString(f);
                Matcher pin = GO_PIN.matcher(text);
                while (pin.find()) {
                    String v = pin.group(1);
                    if (SbomReplyRenderer.VERSION_ORDER.compare(v, target) < 0) {
                        problems.add(rel + ": Go " + v + " < " + target);
                        continue;
                    }
                    Map<String, String> sums = official.computeIfAbsent(v, this::goSha256);
                    if (sums.isEmpty()) {
                        problems.add(rel + ": go.dev 에 Go " + v + " 배포가 없어요");
                        continue;
                    }
                    Matcher sha = GO_SHA.matcher(text);
                    while (sha.find()) {
                        String arch = sha.group(1) == null || !sha.group(1).equals("ARM64") ? "amd64" : "arm64";
                        String expected = sums.get(arch);
                        if (expected != null && !expected.equals(sha.group(2))) {
                            problems.add(rel + ": GO_SHA256" + (sha.group(1) == null ? "" : "_" + sha.group(1))
                                    + " 가 go.dev 의 go" + v + " " + arch + " 값과 달라요");
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return problems;
    }

    // go.dev 의 공식 linux tar.gz sha256 (amd64, arm64)
    Map<String, String> goSha256(String version) {
        Map<String, String> out = new HashMap<>();
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create("https://go.dev/dl/?mode=json&include=all"))
                    .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            for (JsonNode rel : objectMapper.readTree(resp.body())) {
                if (!("go" + version).equals(rel.path("version").asText())) {
                    continue;
                }
                for (JsonNode f : rel.path("files")) {
                    if ("linux".equals(f.path("os").asText()) && "archive".equals(f.path("kind").asText())) {
                        out.put(f.path("arch").asText(), f.path("sha256").asText());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }
}
