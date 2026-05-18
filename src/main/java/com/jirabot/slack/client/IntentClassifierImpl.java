package com.jirabot.slack.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.IntentProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// STUDY: Haiku 전용 의도 분류기. Sonnet(ClaudeApiClient)과 분리하여 역할별 독립 관리.
//        --bare로 CLAUDE.md/skill/hook 로드를 스킵하고 --system-prompt-file로 분류 프롬프트만 전달.
@Component
public class IntentClassifierImpl implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifierImpl.class);

    private final ProcessRunner processRunner;
    private final IntentProperties props;
    private final ObjectMapper objectMapper;

    public IntentClassifierImpl(ProcessRunner processRunner, IntentProperties props, ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntentResult classify(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return IntentResult.unknown(rawText);
        }
        try {
            List<String> command = buildCommand();
            Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
            // STUDY: stdin에는 사용자 메시지만 전달. 시스템 프롬프트는 --system-prompt-file로 분리.
            //        매우 짧은 입력 ("안녕하세요", "ok") 을 모델이 시스템에 대한 직접 인사로 받아
            //        비-JSON 대화 응답을 내는 회귀가 있어, 분류 대상임을 명시적으로 프레이밍한다.
            String framedInput = "Classify this user message: " + rawText;
            ProcessRunner.Result result = processRunner.run(command, framedInput, timeout);

            if (result.timedOut()) {
                log.warn("Haiku intent classifier timed out after {}s", props.timeoutSeconds());
                return IntentResult.unknown(rawText);
            }
            if (result.exitCode() != 0) {
                log.warn("Haiku intent classifier exited with code={}", result.exitCode());
                return IntentResult.unknown(rawText);
            }
            if (result.stdout() == null || result.stdout().isBlank()) {
                log.warn("Haiku intent classifier returned empty stdout");
                return IntentResult.unknown(rawText);
            }
            return parseEnvelope(result.stdout(), rawText);
        } catch (Exception e) {
            log.warn("Haiku intent classification failed: {}", e.toString());
            return IntentResult.unknown(rawText);
        }
    }

    // STUDY: --bare는 OAuth 인증도 스킵하므로 사용 불가 (CLI 구독 기반 인증 필요).
    //        --system-prompt-file로 분류 프롬프트를 전달하고 CLAUDE.md는 자동 로드되지만
    //        분류 결과에 영향 없음 (system-prompt-file이 우선).
    private List<String> buildCommand() {
        return List.of(
                props.cliPath(), "-p",
                "--system-prompt-file", props.promptFile(),
                "--output-format", "json",
                "--max-turns", "1",
                "--model", props.model()
        );
    }

    private IntentResult parseEnvelope(String stdout, String rawText) {
        try {
            JsonNode envelope = objectMapper.readTree(stdout);
            if (envelope.path("is_error").asBoolean(false)) {
                log.warn("Haiku reported is_error=true");
                return IntentResult.unknown(rawText);
            }
            String inner = envelope.path("result").asText("");
            if (inner.isBlank()) {
                log.warn("Haiku envelope has blank result");
                return IntentResult.unknown(rawText);
            }
            String stripped = stripToJsonObject(inner);
            return objectMapper.readValue(stripped, IntentResult.class);
        } catch (Exception e) {
            log.warn("Haiku JSON parse failed: {}", e.toString());
            return IntentResult.unknown(rawText);
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
