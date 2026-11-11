package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.client.process.DefaultProcessRunner;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.ClaudeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 실제 git 으로 repo/(저장소) + agent/(.git 없는 작업 트리 사본) 구성을 만들어 검사한다.
class DiffGuardTest {

    @TempDir
    Path root;
    private final ProcessRunner runner = new DefaultProcessRunner(
            new ClaudeProperties("claude", "m", "h", 5, "plan", 1, true));
    private FixWorkspace.Workspace ws;
    private DiffGuard guard;

    private void sh(Path cwd, String... cmd) {
        ProcessRunner.Result r = runner.run(List.of(cmd), null, Duration.ofSeconds(30), cwd, Map.of());
        assertThat(r.success()).as(String.join(" ", cmd) + " → " + r.stderr()).isTrue();
    }

    @BeforeEach
    void setUp() throws Exception {
        Path repo = root.resolve("repo");
        Path agent = root.resolve("agent");
        Files.createDirectories(repo.resolve("services"));
        Files.writeString(repo.resolve("services/go.mod"), "module x\n\ngo 1.26.8\n\nrequire golang.org/x/net v0.60.0\n");
        Files.writeString(repo.resolve("services/go.sum"), "golang.org/x/net v0.60.0 h1:a\n");
        Files.writeString(repo.resolve("Dockerfile"), "FROM golang:1.26\nARG GO_VERSION=1.26.8\n");
        Files.writeString(repo.resolve("main.go"), "package main\n");
        sh(repo, "git", "init", "-q");
        sh(repo, "git", "add", ".");
        sh(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        sh(root, "rsync", "-a", "--exclude=.git", repo + "/", agent + "/");
        ws = new FixWorkspace.Workspace("x", root, repo, agent, List.of(), List.of());
        guard = new DiffGuard(runner, new CiFixProperties(true, "t", null, 0, 0, 0, null, null, null, null, null, null, 10));
    }

    @Test
    void allowedDependencyChange_passes_withDiffWithoutGoSum() throws Exception {
        Files.writeString(ws.agentDir().resolve("services/go.mod"), "module x\n\ngo 1.26.8\n\nrequire golang.org/x/net v0.62.0\n");
        Files.writeString(ws.agentDir().resolve("services/go.sum"), "golang.org/x/net v0.62.0 h1:b\n".repeat(50));
        Files.writeString(ws.agentDir().resolve("Dockerfile"), "FROM golang:1.26\nARG GO_VERSION=1.26.9\n");

        DiffGuard.Verdict v = guard.check(ws);

        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.files()).containsExactlyInAnyOrder("services/go.mod", "services/go.sum", "Dockerfile");
        assertThat(v.changedLines()).isEqualTo(4);          // go.sum 50줄은 세지 않는다
        assertThat(v.diff()).contains("v0.62.0").doesNotContain("h1:b");
    }

    @Test
    void sourceChange_isRejected() throws Exception {
        Files.writeString(ws.agentDir().resolve("main.go"), "package main // evil\n");

        assertThat(guard.check(ws)).satisfies(v -> {
            assertThat(v.ok()).isFalse();
            assertThat(v.reason()).contains("허용되지 않은 파일").contains("main.go");
        });
    }

    @Test
    void deletion_symlink_workflow_andNoChange_areRejected() throws Exception {
        assertThat(guard.check(ws).reason()).isEqualTo("바뀐 파일이 없어요");

        Files.delete(ws.agentDir().resolve("Dockerfile"));
        assertThat(guard.check(ws).reason()).contains("삭제");
        Files.writeString(ws.agentDir().resolve("Dockerfile"), "FROM golang:1.26\nARG GO_VERSION=1.26.8\n");

        Files.delete(ws.agentDir().resolve("services/go.sum"));
        Files.createSymbolicLink(ws.agentDir().resolve("services/go.sum"), Path.of("/etc/passwd"));
        assertThat(guard.check(ws).reason()).contains("심볼릭 링크");
        Files.delete(ws.agentDir().resolve("services/go.sum"));
        Files.writeString(ws.agentDir().resolve("services/go.sum"), "golang.org/x/net v0.60.0 h1:a\n");

        Files.createDirectories(ws.agentDir().resolve(".github/workflows"));
        Files.writeString(ws.agentDir().resolve(".github/workflows/Dockerfile.yml"), "x\n");
        assertThat(guard.check(ws).ok()).isFalse();
    }

    @Test
    void goSumOnlyChange_isRejected_becauseNoVersionMoved() throws Exception {
        Files.writeString(ws.agentDir().resolve("services/go.sum"), "golang.org/x/net v0.60.0 h1:a\nextra h1:c\n");

        assertThat(guard.check(ws)).satisfies(v -> {
            assertThat(v.ok()).isFalse();
            assertThat(v.reason()).contains("go.sum 만 바뀌었어요");
        });
    }

    @Test
    void tooLargeChange_includingNewFiles_isRejected() throws Exception {
        Files.writeString(ws.agentDir().resolve("build.Dockerfile"), "RUN x\n".repeat(11));   // 새 파일 11줄 > 상한 10

        assertThat(guard.check(ws).reason()).contains("변경이 너무 커요");
    }

    // ── 연속 실패 수정용 정책 (v0.0.100) ─────────────────────

    private static final DiffGuard.Policy CODE = DiffGuard.Policy.code(20, 3);

    @Test
    void codePolicy_allowsSourceScriptsAndWorkflows_withNewFilesInTheDiff() throws Exception {
        Files.writeString(ws.agentDir().resolve("main.go"), "package main\n\nfunc main() {}\n");
        Files.createDirectories(ws.agentDir().resolve(".github/workflows"));
        Files.writeString(ws.agentDir().resolve(".github/workflows/ci.yaml"), "on: push\njobs: {}\n");

        DiffGuard.Verdict v = guard.check(ws, CODE);

        assertThat(v.ok()).as(v.reason()).isTrue();
        assertThat(v.files()).containsExactlyInAnyOrder("main.go", ".github/workflows/ci.yaml");
        assertThat(v.changedLines()).isEqualTo(4);
        assertThat(v.diff()).contains("func main() {}", "+++ b/.github/workflows/ci.yaml", "+on: push");
        assertThat(guard.check(ws).ok()).as("같은 변경도 SBOM 정책에서는 거부").isFalse();
    }

    @Test
    void codePolicy_rejectsSecretsAgentInstructionsAndOtherGithubPaths() throws Exception {
        for (String path : List.of(".env.production", "deploy/server.key", ".claude/settings.json", "CLAUDE.md",
                "docs/AGENTS.md", ".gitmodules", ".github/CODEOWNERS", ".github/dependabot.yml", ".mcp.json")) {
            Path f = ws.agentDir().resolve(path);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "x\n");
            assertThat(guard.check(ws, CODE).reason()).as(path).isEqualTo("허용되지 않은 파일을 바꿨어요: " + path);
            Files.delete(f);
        }
    }

    @Test
    void codePolicy_rejectsChangesThatHideTheFailure() throws Exception {
        Files.createDirectories(ws.agentDir().resolve(".github/workflows"));
        Files.writeString(ws.agentDir().resolve(".github/workflows/ci.yaml"), "jobs:\n  t:\n    continue-on-error: true\n");
        assertThat(guard.check(ws, CODE).reason()).isEqualTo("실패를 숨기는 수정은 허용하지 않아요: continue-on-error: true");
        Files.delete(ws.agentDir().resolve(".github/workflows/ci.yaml"));

        Files.writeString(ws.agentDir().resolve("Dockerfile"), "FROM golang:1.26\nARG GO_VERSION=1.26.8\nRUN make test || true\n");
        assertThat(guard.check(ws, CODE).reason()).contains("실패를 숨기는 수정", "make test || true");
    }

    @Test
    void codePolicy_rejectsDeletionTooManyFilesTooManyLinesAndBinary() throws Exception {
        Files.delete(ws.agentDir().resolve("main.go"));
        assertThat(guard.check(ws, CODE).reason()).contains("삭제");
        Files.writeString(ws.agentDir().resolve("main.go"), "package main\n");

        for (String f : List.of("a.sh", "b.sh", "c.sh", "d.sh")) {
            Files.writeString(ws.agentDir().resolve(f), "echo\n");
        }
        assertThat(guard.check(ws, CODE).reason()).isEqualTo("바꾼 파일이 너무 많아요 (4개, 상한 3개)");
        for (String f : List.of("b.sh", "c.sh", "d.sh")) {
            Files.delete(ws.agentDir().resolve(f));
        }

        Files.writeString(ws.agentDir().resolve("a.sh"), "echo\n".repeat(21));
        assertThat(guard.check(ws, CODE).reason()).contains("변경이 너무 커요 (21줄, 상한 20줄)");
        Files.delete(ws.agentDir().resolve("a.sh"));

        Files.write(ws.agentDir().resolve("main.go"), new byte[] {0, 1, 2, (byte) 0xff, 0, 7});
        assertThat(guard.check(ws, CODE).reason()).isEqualTo("바이너리 파일 변경은 허용하지 않아요: main.go");
    }
}
