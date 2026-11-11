package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.ClaudeProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FixAgentRunnerTest {

    private ProcessRunner runner;
    private FixAgentRunner agent;
    private final FixWorkspace.Workspace ws = new FixWorkspace.Workspace("HE-KMS", Path.of("/w"), Path.of("/w/repo"),
            Path.of("/w/agent"), List.of(), List.of());

    @BeforeEach
    void setUp() {
        runner = mock(ProcessRunner.class);
        when(runner.run(eq(List.of("id", "-u")), isNull(), any(Duration.class))).thenReturn(new ProcessRunner.Result(0, "1001\n", "", false));
        when(runner.run(eq(List.of("id", "-g")), isNull(), any(Duration.class))).thenReturn(new ProcessRunner.Result(0, "1002\n", "", false));
        CiFixProperties fp = new CiFixProperties(true, "SECRET-OAUTH", null, 20, 2, 60, null, null, null, null, null, null, 0);
        agent = new FixAgentRunner(runner, fp, new ClaudeProperties("claude", "claude-sonnet-4-6", "h", 5, "plan", 1, true),
                new ObjectMapper());
    }

    private static String envelope(String result, boolean error) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("type", "result", "is_error", error, "result", result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_passesTokenOnlyViaEnv_mountsOnlyWorktreeAndPrompts() throws Exception {
        when(runner.run(any(), anyString(), any(Duration.class), isNull(), anyMap())).thenReturn(new ProcessRunner.Result(0,
                envelope("{\"changed\":true,\"title\":\"fix(deps): bump x\",\"summary\":\"s\",\"verification\":\"v\",\"notes\":\"\"}", false),
                "", false));

        FixAgentRunner.AgentResult r = agent.run(ws, "TASK", "job-1");

        assertThat(r.ok()).isTrue();
        assertThat(r.changed()).isTrue();
        assertThat(r.title()).isEqualTo("fix(deps): bump x");
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(runner).run(cmd.capture(), eq("TASK"), eq(Duration.ofMinutes(20)), isNull(), env.capture());
        assertThat(String.join(" ", cmd.getValue())).doesNotContain("SECRET-OAUTH")
                .contains("--preserve-env=CLAUDE_CODE_OAUTH_TOKEN", "-e CLAUDE_CODE_OAUTH_TOKEN ", "--user 1001:1002",
                        "-v /w/agent:/work", ":/prompts:ro", "--permission-mode bypassPermissions", "--model claude-sonnet-4-6",
                        "--append-system-prompt-file /prompts/sbom-fix-agent.md", "--strict-mcp-config --mcp-config {\"mcpServers\":{}}");
        assertThat(cmd.getValue()).doesNotContain("/w/repo:/work");
        assertThat(env.getValue()).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "SECRET-OAUTH");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runRaw_usesTheGivenModelEffortPromptAndLimits_andReturnsTheAgentJson() throws Exception {
        when(runner.run(any(), anyString(), any(Duration.class), isNull(), anyMap())).thenReturn(new ProcessRunner.Result(0,
                envelope("{\"outcome\":\"not_fixable\",\"root_cause\":\"r\"}", false), "", false));

        FixAgentRunner.Raw raw = agent.runRaw(ws, "TASK", "job-2", new FixAgentRunner.AgentSpec("claude-opus-5-5", "high",
                "prompts/ci-fix-agent.md", 100, 30, List.of("GOTOOLCHAIN=auto")));

        assertThat(raw.ok()).isTrue();
        assertThat(raw.out().path("outcome").asText()).isEqualTo("not_fixable");
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), eq("TASK"), eq(Duration.ofMinutes(30)), isNull(), anyMap());
        assertThat(String.join(" ", cmd.getValue())).doesNotContain("SECRET-OAUTH", "GOFLAGS")
                .contains("--name fix-job-2", "-e GOTOOLCHAIN=auto", "--max-turns 100", "--model claude-opus-5-5 --effort high",
                        "--append-system-prompt-file /prompts/ci-fix-agent.md", "--strict-mcp-config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sbomRun_keepsItsEnvAndSendsNoEffortFlag() throws Exception {
        when(runner.run(any(), anyString(), any(Duration.class), isNull(), anyMap())).thenReturn(new ProcessRunner.Result(0,
                envelope("{\"changed\":false}", false), "", false));

        agent.run(ws, "TASK", "job-3");

        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(runner).run(cmd.capture(), eq("TASK"), eq(Duration.ofMinutes(20)), isNull(), anyMap());
        assertThat(String.join(" ", cmd.getValue())).contains("-e GOTOOLCHAIN=auto -e GOFLAGS=-mod=mod").doesNotContain("--effort");
    }

    @Test
    void parse_errorEnvelopeAndGarbage_areFailures() throws Exception {
        assertThat(agent.parse(new ProcessRunner.Result(1, envelope("x", true), "auth failed", false)).error())
                .startsWith("에이전트 실행 오류").contains("auth failed");
        assertThat(agent.parse(new ProcessRunner.Result(0, "not json", "", false)).ok()).isFalse();
        assertThat(agent.parse(new ProcessRunner.Result(0, "{\"subtype\":\"error_max_turns\"}", "", false)).error())
                .contains("error_max_turns");
    }

    @Test
    void timeout_removesTheContainerByName() {
        when(runner.run(any(), anyString(), any(Duration.class), isNull(), anyMap()))
                .thenReturn(new ProcessRunner.Result(-1, "", "", true));

        FixAgentRunner.AgentResult r = agent.run(ws, "TASK", "job-2");

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("20분");
        verify(runner).run(eq(List.of("sudo", "-n", "docker", "rm", "-f", "fix-job-2")), isNull(), any(Duration.class));
    }

    @Test
    void ensureImage_buildsOnlyWhenMissing_andFailsLoudly() {
        when(runner.run(eq(List.of("sudo", "-n", "docker", "image", "inspect", "jirabot-fix-agent:2")), isNull(), any(Duration.class)))
                .thenReturn(new ProcessRunner.Result(1, "", "No such image", false));
        when(runner.run(eq(List.of("sudo", "-n", "docker", "build", "-q", "-t", "jirabot-fix-agent:2", "docker/fix-agent")),
                isNull(), any(Duration.class))).thenReturn(new ProcessRunner.Result(1, "", "network down", false));

        assertThatThrownBy(() -> agent.ensureImage()).hasMessageContaining("network down");
    }
}
