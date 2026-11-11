package com.jirabot.slack.cialert;

import com.jirabot.slack.client.process.ProcessRunner;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

// STUDY: 에이전트 변경을 커밋하기 전의 결정적 검사 (v0.0.94). repo/.git 을 저장소로, agent/ 를 작업 트리로 놓고
//        git status 로 무엇이 바뀌었는지 본다. 의존성 수정에 필요한 파일(go.mod·go.sum·Dockerfile)만 허용하고,
//        삭제·심볼릭 링크·실행 권한 변경·큰 변경은 거부한다 — 에이전트가 무엇을 하든 PR 에 들어갈 수 있는 범위를 고정한다.
//        연속 실패 수정(v0.0.100)은 Policy.code 로 범위만 넓혀 같은 검사를 쓴다.
@Component
public class DiffGuard {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    static final Pattern ALLOWED = Pattern.compile(
            "(?:^|.*/)go\\.(?:mod|sum)$|(?:^|.*/)go\\.work(?:\\.sum)?$|(?:^|.*/)Dockerfile[^/]*$|.*\\.Dockerfile$");

    private final ProcessRunner runner;
    private final CiFixProperties fixProps;

    public DiffGuard(ProcessRunner runner, CiFixProperties fixProps) {
        this.runner = runner;
        this.fixProps = fixProps;
    }

    // STUDY: 검사 규칙 묶음 (v0.0.100). SBOM 수정은 의존성 파일만(sbom), 연속 실패 수정은 코드·워크플로까지(code) 허용한다.
    //        allowed 가 null 이면 denied 에 걸리지 않는 모든 경로를 허용한다. .github/ 는 githubDirs 에 맞는 경로만.
    public record Policy(Pattern allowed, Pattern denied, Pattern githubDirs, int maxLines, int maxFiles,
                         boolean rejectSumOnly, boolean rejectBinary, List<Pattern> forbiddenAdditions) {

        static Policy sbom(int maxLines) {
            return new Policy(ALLOWED, null, null, maxLines, 0, true, false, List.of());
        }

        // 비밀값·에이전트 지침·저장소 구조 파일은 못 바꾸고, 실패를 숨기는 줄은 못 넣는다.
        public static Policy code(int maxLines, int maxFiles) {
            return new Policy(null, CODE_DENIED, CODE_GITHUB_DIRS, maxLines, maxFiles, false, true, HIDING_FAILURE);
        }

        boolean permits(String path) {
            if (path.startsWith(".github/")) {
                return githubDirs != null && githubDirs.matcher(path).matches();
            }
            if (denied != null && denied.matcher(path).matches()) {
                return false;
            }
            return allowed == null || allowed.matcher(path).matches();
        }
    }

    static final Pattern CODE_DENIED = Pattern.compile(
            "(?:^|.*/)\\.env[^/]*$|.*\\.(?:pem|key|p12|pfx|jks)$|(?:^|.*/)id_(?:rsa|ed25519|ecdsa)[^/]*$"
                    + "|^\\.claude/.*|(?:^|.*/)(?:CLAUDE|AGENTS)\\.md$|^\\.mcp\\.json$|^\\.gitmodules$|(?:^|.*/)CODEOWNERS$");
    static final Pattern CODE_GITHUB_DIRS = Pattern.compile("^\\.github/(?:workflows|actions)/.+");
    // 추가된 줄에 있으면 거부: 실패해도 넘어가게 만드는 표현
    static final List<Pattern> HIDING_FAILURE = List.of(
            Pattern.compile("continue-on-error:\\s*true"), Pattern.compile("\\|\\|\\s*true\\b"));

    public record Verdict(boolean ok, List<String> files, int changedLines, String reason, String diff) {
        static Verdict reject(String reason, List<String> files) {
            return new Verdict(false, files, 0, reason, "");
        }
    }

    public Verdict check(FixWorkspace.Workspace ws) {
        return check(ws, Policy.sbom(fixProps.maxDiffLines()));
    }

    public Verdict check(FixWorkspace.Workspace ws, Policy policy) {
        // STUDY: --ignore-submodules=all — 서브모듈 경로(받아온 소스·빈 모듈)는 비교에서 뺀다. 그 안의 변경은 PR 에 못 들어간다.
        String status = git(ws, "status", "--porcelain=v1", "-z", "--ignore-submodules=all", "--untracked-files=all");
        List<String> files = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        for (String entry : status.split("\0")) {
            if (entry.length() < 4) {
                continue;
            }
            String code = entry.substring(0, 2);
            String path = entry.substring(3);
            if (code.contains("D")) {
                return Verdict.reject("파일 삭제는 허용하지 않아요: " + path, files);
            }
            if (code.contains("T") || Files.isSymbolicLink(ws.agentDir().resolve(path))) {
                return Verdict.reject("심볼릭 링크·파일 종류 변경은 허용하지 않아요: " + path, files);
            }
            if (!policy.permits(path)) {
                return Verdict.reject("허용되지 않은 파일을 바꿨어요: " + path, files);
            }
            files.add(path);
            if (code.equals("??")) {
                untracked.add(path);
            }
        }
        if (files.isEmpty()) {
            return Verdict.reject("바뀐 파일이 없어요", files);
        }
        // go.sum 만 바뀐 건 의존성 버전이 그대로라는 뜻 — 취약점 수정이 아니다 (v0.0.98, go mod download 부산물 등).
        if (policy.maxFiles() > 0 && files.size() > policy.maxFiles()) {
            return Verdict.reject("바꾼 파일이 너무 많아요 (" + files.size() + "개, 상한 " + policy.maxFiles() + "개)", files);
        }
        if (policy.rejectSumOnly() && files.stream().allMatch(f -> f.endsWith("go.sum"))) {
            return Verdict.reject("go.sum 만 바뀌었어요 (의존성 버전은 그대로)", files);
        }
        String summary = git(ws, "diff", "--summary", "--ignore-submodules=all");
        if (summary.contains("mode change")) {
            return Verdict.reject("실행 권한 변경은 허용하지 않아요", files);
        }
        int lines = 0;
        for (String l : git(ws, "diff", "--numstat", "--ignore-submodules=all").split("\n")) {
            String[] p = l.split("\t");
            if (p.length == 3 && p[0].equals("-") && policy.rejectBinary()) {
                return Verdict.reject("바이너리 파일 변경은 허용하지 않아요: " + p[2], files);
            }
            if (p.length == 3 && !p[2].endsWith("go.sum") && !p[0].equals("-")) {
                lines += Integer.parseInt(p[0]) + Integer.parseInt(p[1]);
            }
        }
        // 새로 생긴 파일은 git diff 에 안 잡히므로 줄 수를 따로 더하고, 내용을 diff 뒤에 붙인다 (go.sum 제외).
        StringBuilder added = new StringBuilder();
        for (String path : untracked) {
            if (!path.endsWith("go.sum")) {
                try (java.util.stream.Stream<String> st = Files.lines(ws.agentDir().resolve(path))) {
                    List<String> content = st.toList();
                    lines += content.size();
                    added.append("--- /dev/null\n+++ b/").append(path).append('\n');
                    content.forEach(c -> added.append('+').append(c).append('\n'));
                } catch (java.io.IOException | java.io.UncheckedIOException e) {
                    return Verdict.reject("새 파일을 읽지 못했어요: " + path, files);
                }
            }
        }
        if (lines > policy.maxLines()) {
            return Verdict.reject("변경이 너무 커요 (" + lines + "줄, 상한 " + policy.maxLines() + "줄)", files);
        }
        String diff = git(ws, "diff", "--ignore-submodules=all", "--", ":(exclude)*go.sum");
        if (!policy.forbiddenAdditions().isEmpty()) {
            diff = diff + added;
            for (String l : diff.split("\n")) {
                if (!l.startsWith("+") || l.startsWith("+++")) {
                    continue;
                }
                for (Pattern bad : policy.forbiddenAdditions()) {
                    if (bad.matcher(l).find()) {
                        return Verdict.reject("실패를 숨기는 수정은 허용하지 않아요: " + l.substring(1).strip(), files);
                    }
                }
            }
        }
        return new Verdict(true, files, lines, "", diff);
    }

    private String git(FixWorkspace.Workspace ws, String... args) {
        List<String> cmd = new ArrayList<>(FixWorkspace.worktreeGit(ws));
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = runner.run(cmd, null, TIMEOUT, ws.root(), Map.of());
        if (!r.success()) {
            throw new IllegalStateException("git " + args[0] + " 실패: " + (r.stderr() == null ? "" : r.stderr().strip()));
        }
        return r.stdout() == null ? "" : r.stdout();
    }
}
