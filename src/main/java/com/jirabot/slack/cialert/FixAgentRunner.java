package com.jirabot.slack.cialert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.process.ClaudeCliFlags;
import com.jirabot.slack.client.process.ClaudeJson;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.ClaudeProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// STUDY: 수정 에이전트 실행 (v0.0.94). `claude -p` 를 docker 컨테이너 안에서 돌린다.
//        - 마운트: 작업 트리(agent/, .git 없음)와 프롬프트 디렉터리(읽기 전용)뿐. .env·GitHub 토큰은 들어가지 않는다.
//        - 비밀값은 CLAUDE_CODE_OAUTH_TOKEN 하나. 명령 인자가 아니라 env 로 넘기고(sudo --preserve-env) docker 는 -e 이름만 받는다.
//        - --append-system-prompt-file: Claude Code 기본 프롬프트(도구 사용법)에 수정 규칙을 덧붙인다.
//          대상 레포의 CLAUDE.md·.claude/skills(예: envector 의 fix-sbom-cve)도 그대로 읽힌다.
@Component
public class FixAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(FixAgentRunner.class);
    private static final String TOKEN_ENV = "CLAUDE_CODE_OAUTH_TOKEN";

    private final ProcessRunner runner;
    private final CiFixProperties fixProps;
    private final ClaudeProperties claudeProps;
    private final ObjectMapper objectMapper;

    public FixAgentRunner(ProcessRunner runner, CiFixProperties fixProps, ClaudeProperties claudeProps,
                          ObjectMapper objectMapper) {
        this.runner = runner;
        this.fixProps = fixProps;
        this.claudeProps = claudeProps;
        this.objectMapper = objectMapper;
    }

    public record AgentResult(boolean ok, boolean changed, String title, String summary, String verification,
                              String notes, String error) {
        static AgentResult failed(String error) {
            return new AgentResult(false, false, "", "", "", "", error);
        }
    }

    // 이미지가 없을 때만 빌드한다 (처음 한 번, 수 분).
    public void ensureImage() {
        ProcessRunner.Result inspect = runner.run(List.of("sudo", "-n", "docker", "image", "inspect", fixProps.agentImage()),
                null, Duration.ofSeconds(30));
        if (inspect.success()) {
            return;
        }
        log.info("building fix agent image {} from {}", fixProps.agentImage(), fixProps.agentDockerDir());
        ProcessRunner.Result build = runner.run(List.of("sudo", "-n", "docker", "build", "-q", "-t", fixProps.agentImage(),
                fixProps.agentDockerDir()), null, Duration.ofMinutes(20));
        if (!build.success()) {
            throw new IllegalStateException("에이전트 이미지 빌드 실패: " + tail(build.stderr()));
        }
    }

    // STUDY: 에이전트 한 번의 실행 조건 (v0.0.100). SBOM 수정과 연속 실패 수정이 같은 컨테이너 실행을 쓰되
    //        모델·effort·프롬프트·시간만 다르게 준다. env 는 컨테이너에 넘길 "이름=값" (비밀값 아님).
    public record AgentSpec(String model, String effort, String promptFile, int maxTurns, int timeoutMinutes,
                            List<String> env) {}

    // 에이전트의 마지막 메시지(JSON)를 그대로 돌려준다. 필드 해석은 부르는 쪽이 한다.
    public record Raw(boolean ok, JsonNode out, String error) {
        static Raw failed(String error) {
            return new Raw(false, null, error);
        }
    }

    public AgentResult run(FixWorkspace.Workspace ws, String task, String jobId) {
        String model = fixProps.model().isBlank() ? claudeProps.model() : fixProps.model();
        return toResult(runRaw(ws, task, jobId, new AgentSpec(model, "", fixProps.promptFile(), fixProps.maxTurns(),
                fixProps.timeoutMinutes(), List.of("GOTOOLCHAIN=auto", "GOFLAGS=-mod=mod"))));
    }

    public Raw runRaw(FixWorkspace.Workspace ws, String task, String jobId, AgentSpec spec) {
        String container = "fix-" + jobId;
        Path promptFile = Path.of(spec.promptFile()).toAbsolutePath();
        List<String> cmd = new ArrayList<>(List.of("sudo", "-n", "--preserve-env=" + TOKEN_ENV, "docker", "run", "--rm", "-i",
                "--name", container, "--user", uidGid(), "--memory", "4g", "--cpus", "2",
                // HOME=/tmp: --user 로 준 호스트 uid 는 이미지에 계정이 없어 HOME 이 / 가 된다 — Claude·Go 캐시를 쓸 곳.
                "-e", TOKEN_ENV, "-e", "HOME=/tmp"));
        for (String e : spec.env()) {
            cmd.add("-e");
            cmd.add(e);
        }
        cmd.addAll(List.of("-v", ws.agentDir() + ":/work", "-v", promptFile.getParent() + ":/prompts:ro", "-w", "/work",
                fixProps.agentImage(),
                "claude", "-p", "--output-format", "json", "--permission-mode", "bypassPermissions",
                "--max-turns", String.valueOf(spec.maxTurns()), "--model", spec.model()));
        if (spec.effort() != null && !spec.effort().isBlank()) {
            cmd.add("--effort");
            cmd.add(spec.effort());
        }
        // 대상 레포의 .mcp.json 같은 MCP 설정도 무시한다 — bypassPermissions 라 도구는 내장 도구로만 (v0.0.96).
        cmd.addAll(List.of("--strict-mcp-config", "--mcp-config", ClaudeCliFlags.NO_MCP_SERVERS,
                "--append-system-prompt-file", "/prompts/" + promptFile.getFileName()));
        ProcessRunner.Result r = runner.run(cmd, task, Duration.ofMinutes(spec.timeoutMinutes()), null,
                Map.of(TOKEN_ENV, fixProps.oauthToken()));
        if (r.timedOut()) {
            // STUDY: sudo 프로세스를 죽여도 docker 데몬 쪽 컨테이너는 남을 수 있어 이름으로 지운다.
            runner.run(List.of("sudo", "-n", "docker", "rm", "-f", container), null, Duration.ofSeconds(60));
            return Raw.failed("에이전트가 " + spec.timeoutMinutes() + "분 안에 끝나지 않았어요");
        }
        return parseRaw(r);
    }

    AgentResult parse(ProcessRunner.Result r) {
        return toResult(parseRaw(r));
    }

    private static AgentResult toResult(Raw raw) {
        if (!raw.ok()) {
            return AgentResult.failed(raw.error());
        }
        JsonNode out = raw.out();
        return new AgentResult(true, out.path("changed").asBoolean(false), out.path("title").asText(""),
                out.path("summary").asText(""), out.path("verification").asText(""), out.path("notes").asText(""), "");
    }

    Raw parseRaw(ProcessRunner.Result r) {
        try {
            JsonNode envelope = objectMapper.readTree(r.stdout() == null || r.stdout().isBlank() ? "{}" : r.stdout());
            if (envelope.path("is_error").asBoolean(false) || !envelope.has("result")) {
                String why = envelope.path("subtype").asText("");
                return Raw.failed("에이전트 실행 오류" + (why.isBlank() ? "" : " (" + why + ")")
                        + (r.exitCode() != 0 ? ": " + tail(r.stderr()) : ""));
            }
            return new Raw(true, objectMapper.readTree(ClaudeJson.stripToJsonObject(envelope.path("result").asText(""))), "");
        } catch (Exception e) {
            log.warn("fix agent output parse failed: {} stderr={}", e.toString(), tail(r.stderr()));
            return Raw.failed("에이전트 응답을 읽지 못했어요 (exit " + r.exitCode() + ")");
        }
    }

    private String uidGid() {
        ProcessRunner.Result u = runner.run(List.of("id", "-u"), null, Duration.ofSeconds(5));
        ProcessRunner.Result g = runner.run(List.of("id", "-g"), null, Duration.ofSeconds(5));
        return orDefault(u.stdout()) + ":" + orDefault(g.stdout());
    }

    private static String orDefault(String id) {
        return id == null || id.isBlank() ? "1000" : id.strip();
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 300 ? "…" + t.substring(t.length() - 300) : t;
    }
}
