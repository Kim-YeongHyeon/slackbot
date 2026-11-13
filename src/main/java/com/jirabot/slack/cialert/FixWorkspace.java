package com.jirabot.slack.cialert;

import com.jirabot.slack.client.process.ProcessRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// STUDY: 수정 작업 사본 (v0.0.94). repo/ 는 Java 만 쓰는 git 저장소, agent/ 는 .git 을 뺀 작업 트리 사본으로
//        에이전트 컨테이너에는 agent/ 만 마운트한다 — .git(hook·config)을 심어 호스트 git 실행을 노리는 경로를 막는다.
//        go.mod 의 replace 가 가리키는 서브모듈은 토큰으로 받을 수 있으면 고정 커밋으로 받고, 못 받으면(비공개)
//        빈 go.mod 로 대신한다 — 그래야 `go get` 이 돈다 (heracles #538 에서 검증한 방식).
@Component
public class FixWorkspace {

    private static final Logger log = LoggerFactory.getLogger(FixWorkspace.class);
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(5);
    private static final Pattern REPLACE_LINE =
            Pattern.compile("^\\s*(?:replace\\s+)?(\\S+)(?:\\s+v\\S+)?\\s+=>\\s+(\\S+)(?:\\s+v\\S+)?\\s*$");
    private static final Pattern SUBMODULE = Pattern.compile("path\\s*=\\s*(\\S+)\\s+url\\s*=\\s*(\\S+)");

    private final ProcessRunner runner;
    private final CiAlertProperties props;
    private final CiFixProperties fixProps;

    public FixWorkspace(ProcessRunner runner, CiAlertProperties props, CiFixProperties fixProps) {
        this.runner = runner;
        this.props = props;
        this.fixProps = fixProps;
    }

    public record Workspace(String repo, Path root, Path repoDir, Path agentDir, List<String> fetched, List<String> stubbed) {}

    record Replace(String module, Path target) {}

    public Workspace prepare(String repo, String jobId) {
        return prepare(repo, jobId, null);
    }

    // branch 가 있으면 그 브랜치를 받는다 (연속 실패 자동 수정은 실패한 run 의 브랜치에서 고친다, v0.0.100).
    public Workspace prepare(String repo, String jobId, String branch) {
        return prepare(repo, jobId, branch, null);
    }

    // STUDY: ref(커밋 sha) 가 있으면 브랜치를 받은 뒤 그 커밋만 얕게 받아 체크아웃한다 (v0.0.103, dry-run 전용).
    //        이미 고쳐진 브랜치에서도 실패 당시 커밋으로 에이전트를 시험해 볼 수 있게. 실제 PR 은 언제나 브랜치 HEAD 기준.
    public Workspace prepare(String repo, String jobId, String branch, String ref) {
        if (ref != null && !ref.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("ref 는 40자 커밋 sha 여야 해요 (얕은 fetch 는 전체 sha 만 받는다): " + ref);
        }
        Path root = Path.of(fixProps.workDir(), jobId);
        Path repoDir = root.resolve("repo");
        Path agentDir = root.resolve("agent");
        try {
            deleteRecursively(root);
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (branch == null || branch.isBlank()) {
            git(root, "clone", "-q", "--depth", "1", "--no-tags", "--single-branch", repoUrl(repo), "repo");
        } else {
            git(root, "clone", "-q", "--depth", "1", "--no-tags", "--single-branch", "--branch", branch, repoUrl(repo), "repo");
        }
        if (ref != null) {
            git(repoDir, "fetch", "-q", "--depth", "1", "origin", ref);
            git(repoDir, "checkout", "-q", "--detach", "FETCH_HEAD");
        }

        Map<String, String> submodules = submodules(repoDir);   // path → repo name
        List<String> fetched = new ArrayList<>();
        List<String> stubbed = new ArrayList<>();
        for (Replace r : replaces(repoDir)) {
            if (Files.exists(r.target().resolve("go.mod"))) {
                continue;
            }
            Optional<String> sub = submodules.keySet().stream()
                    .filter(p -> r.target().startsWith(repoDir.resolve(p))).findFirst();
            if (sub.isPresent() && !fetched.contains(sub.get()) && fetchSubmodule(repoDir, sub.get(), submodules.get(sub.get()))) {
                fetched.add(sub.get());
                if (Files.exists(r.target().resolve("go.mod"))) {
                    continue;
                }
            }
            writeStub(r);
            stubbed.add(r.module());
        }
        run(List.of("rsync", "-a", "--exclude=.git", repoDir + "/", agentDir + "/"), root, Map.of(), "작업 트리 복사");
        log.info("fix workspace ready repo={} fetched={} stubbed={}", repo, fetched, stubbed);
        return new Workspace(repo, root, repoDir, agentDir, fetched, stubbed);
    }

    // 허용 파일만 add → 커밋 → 새 브랜치로 push. hook 은 끈다 (작업 트리는 에이전트가 만진 곳이라).
    public void commitAndPush(Workspace ws, List<String> files, String branch, String message) {
        commitAndPush(ws, files, branch, message, "sol-sbom-bot");
    }

    public void commitAndPush(Workspace ws, List<String> files, String branch, String message, String author) {
        List<String> add = new ArrayList<>(worktreeGit(ws));
        add.addAll(List.of("add", "--"));
        add.addAll(files);
        run(add, ws.root(), Map.of(), "git add");
        List<String> commit = new ArrayList<>(worktreeGit(ws));
        commit.addAll(List.of("-c", "user.name=" + author, "-c", "user.email=" + author + "@users.noreply.github.com",
                "commit", "-q", "-m", message));
        run(commit, ws.root(), Map.of(), "git commit");
        run(List.of("git", "-c", "core.hooksPath=/dev/null", "--git-dir=" + ws.repoDir().resolve(".git"), "push", "-q",
                repoUrl(ws.repo()), "HEAD:refs/heads/" + branch), ws.root(), gitEnv(), "git push");
    }

    // DiffGuard·커밋이 쓰는 git 기본 인자: repo/.git 을 저장소로, agent/ 를 작업 트리로.
    public static List<String> worktreeGit(Workspace ws) {
        return List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
                "--git-dir=" + ws.repoDir().resolve(".git"), "--work-tree=" + ws.agentDir());
    }

    public void cleanup(Workspace ws) {
        try {
            deleteRecursively(ws.root());
        } catch (IOException e) {
            log.warn("fix workspace cleanup failed {}: {}", ws.root(), e.toString());
        }
    }

    private boolean fetchSubmodule(Path repoDir, String path, String subRepo) {
        if (subRepo == null) {
            return false;
        }
        ProcessRunner.Result pin = runner.run(List.of("git", "ls-tree", "HEAD", path), null, GIT_TIMEOUT, repoDir, Map.of());
        Matcher m = Pattern.compile("160000 commit ([0-9a-f]{40})").matcher(pin.stdout() == null ? "" : pin.stdout());
        if (!pin.success() || !m.find()) {
            return false;
        }
        Path dir = repoDir.resolve(path);
        ProcessRunner.Result init = runner.run(List.of("git", "init", "-q", dir.toString()), null, GIT_TIMEOUT, repoDir, Map.of());
        ProcessRunner.Result fetch = init.success() ? runner.run(List.of("git", "-C", dir.toString(), "fetch", "-q",
                "--depth", "1", repoUrl(subRepo), m.group(1)), null, GIT_TIMEOUT, repoDir, gitEnv()) : init;
        ProcessRunner.Result checkout = fetch.success() ? runner.run(List.of("git", "-C", dir.toString(), "checkout", "-q",
                "FETCH_HEAD"), null, GIT_TIMEOUT, repoDir, Map.of()) : fetch;
        if (!checkout.success()) {
            log.info("submodule {} ({}) not fetchable with the alert token — stubbing replaced modules", path, subRepo);
            try {
                deleteRecursively(dir.resolve(".git"));
            } catch (IOException ignored) {
                // 빈 모듈만 쓰면 되므로 남아도 무방
            }
            return false;
        }
        return true;
    }

    private static void writeStub(Replace r) {
        try {
            Files.createDirectories(r.target());
            Files.writeString(r.target().resolve("go.mod"), "module " + r.module() + "\n\ngo 1.21\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // .gitmodules → path → repo 이름 (같은 org 의 레포만)
    static Map<String, String> submodules(Path repoDir) {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = repoDir.resolve(".gitmodules");
        if (!Files.exists(f)) {
            return out;
        }
        try {
            Matcher m = SUBMODULE.matcher(Files.readString(f));
            while (m.find()) {
                String url = m.group(2).replaceFirst("\\.git$", "");
                out.put(m.group(1), url.substring(Math.max(url.lastIndexOf('/'), url.lastIndexOf(':')) + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    // 레포 안 모든 go.mod 의 로컬 경로 replace (한 줄 형식과 replace ( … ) 블록 모두).
    static List<Replace> replaces(Path repoDir) {
        List<Replace> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repoDir)) {
            for (Path gomod : files.filter(p -> p.getFileName().toString().equals("go.mod"))
                    .filter(p -> !repoDir.relativize(p).toString().matches("(?:.*/)?(?:\\.git|vendor|node_modules)/.*"))
                    .toList()) {
                boolean inBlock = false;
                for (String line : Files.readAllLines(gomod)) {
                    String l = line.replaceFirst("//.*$", "").strip();
                    if (l.startsWith("replace (")) {
                        inBlock = true;
                        continue;
                    }
                    if (inBlock && l.equals(")")) {
                        inBlock = false;
                        continue;
                    }
                    if (!(inBlock || l.startsWith("replace "))) {
                        continue;
                    }
                    Matcher m = REPLACE_LINE.matcher(l);
                    if (m.matches() && (m.group(2).startsWith("./") || m.group(2).startsWith("../"))) {
                        Path target = gomod.getParent().resolve(m.group(2)).normalize();
                        if (target.startsWith(repoDir)) {
                            out.add(new Replace(m.group(1), target));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private String repoUrl(String repo) {
        return "https://github.com/" + props.org() + "/" + repo + ".git";
    }

    // STUDY: 토큰은 명령 인자가 아니라 env 의 git 설정(http.extraHeader)으로만 넘긴다 — ps 나 .git/config 에 남지 않는다.
    private Map<String, String> gitEnv() {
        String basic = Base64.getEncoder().encodeToString(
                ("x-access-token:" + props.githubToken()).getBytes(StandardCharsets.UTF_8));
        return Map.of("GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader",
                "GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basic,
                "GIT_TERMINAL_PROMPT", "0");
    }

    private void git(Path cwd, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        run(cmd, cwd, gitEnv(), "git " + args[0]);
    }

    private void run(List<String> cmd, Path cwd, Map<String, String> env, String what) {
        ProcessRunner.Result r = runner.run(cmd, null, GIT_TIMEOUT, cwd, env);
        if (!r.success()) {
            String err = r.stderr() == null ? "" : r.stderr().strip();
            throw new IllegalStateException(what + " 실패" + (r.timedOut() ? " (시간 초과)" : "") + ": "
                    + (err.length() > 300 ? err.substring(0, 300) : err));
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }
}
