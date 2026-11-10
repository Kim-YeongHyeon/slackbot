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
}
