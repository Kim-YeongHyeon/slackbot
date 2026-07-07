package com.jirabot.slack.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.ThreadActionResult;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.IntentProperties;
import com.jirabot.slack.entity.IssueEntity;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ThreadActionClassifierImpl implements ThreadActionClassifier {

    private static final Logger log = LoggerFactory.getLogger(ThreadActionClassifierImpl.class);
    private static final String PROMPT_FILE = "prompts/haiku-thread-action.md";

    private final ProcessRunner processRunner;
    private final IntentProperties props;
    private final ObjectMapper objectMapper;

    public ThreadActionClassifierImpl(ProcessRunner processRunner, IntentProperties props, ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public ThreadActionResult classify(IssueEntity parentIssue, List<String> threadMessages, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return ThreadActionResult.unknown(userMessage);
        }
        try {
            String stdin = buildStdin(parentIssue, threadMessages, userMessage);
            // STUDY: LEAN_FLAGS — 도구 스키마/CLAUDE.md/동적 섹션 제거 + 세션 기록 중단 (ClaudeCliFlags 참고).
            List<String> command = new java.util.ArrayList<>(List.of(
                    props.cliPath(), "-p",
                    "--system-prompt-file", PROMPT_FILE,
                    "--output-format", "json",
                    "--max-turns", "1",
                    "--model", props.model()
            ));
            command.addAll(com.jirabot.slack.client.process.ClaudeCliFlags.LEAN_FLAGS);
            Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
            ProcessRunner.Result result = processRunner.run(command, stdin, timeout);

            if (result.timedOut()) {
                log.warn("Thread action classifier timed out");
                return ThreadActionResult.unknown(userMessage);
            }
            if (result.exitCode() != 0) {
                log.warn("Thread action classifier exited with code={}", result.exitCode());
                return ThreadActionResult.unknown(userMessage);
            }
            if (result.stdout() == null || result.stdout().isBlank()) {
                log.warn("Thread action classifier returned empty stdout");
                return ThreadActionResult.unknown(userMessage);
            }
            return parseEnvelope(result.stdout(), userMessage);
        } catch (Exception e) {
            log.warn("Thread action classification failed: {}", e.toString());
            return ThreadActionResult.unknown(userMessage);
        }
    }

    private String buildStdin(IssueEntity issue, List<String> threadMessages, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("PARENT ISSUE: %s %s (%s, %s, SP %.0f)\n\n",
                issue.getIssueKey(), issue.getSummary(), issue.getIssueType(),
                issue.getStatus(), issue.getStoryPoint() != null ? issue.getStoryPoint() : 0));
        sb.append("THREAD MESSAGES:\n");
        for (String msg : threadMessages) {
            sb.append(msg).append("\n");
        }
        sb.append("\nUSER ACTION: ").append(userMessage);
        return sb.toString();
    }

    private ThreadActionResult parseEnvelope(String stdout, String rawInput) {
        try {
            JsonNode envelope = objectMapper.readTree(stdout);
            if (envelope.path("is_error").asBoolean(false)) {
                return ThreadActionResult.unknown(rawInput);
            }
            String inner = envelope.path("result").asText("");
            if (inner.isBlank()) {
                return ThreadActionResult.unknown(rawInput);
            }
            String stripped = stripToJsonObject(inner);
            return objectMapper.readValue(stripped, ThreadActionResult.class);
        } catch (Exception e) {
            log.warn("Thread action JSON parse failed: {}", e.toString());
            return ThreadActionResult.unknown(rawInput);
        }
    }

    private static String stripToJsonObject(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        if (!s.startsWith("{")) {
            int open = s.indexOf('{');
            int close = s.lastIndexOf('}');
            if (open >= 0 && close > open) s = s.substring(open, close + 1);
        }
        return s;
    }
}
