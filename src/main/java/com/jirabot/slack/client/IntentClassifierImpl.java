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
//        --system-prompt-file로 분류 프롬프트를 전달. (--bare는 구독 인증을 깨므로 미사용 — lessons L12.)
//        CLI 가 간헐적으로 실패하면 unknown 으로 떨어져 "명령 못 알아들음" 오응답이 되므로 1회 재시도한다.
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

    // 한 번의 시도 결과. RETRYABLE = 일시적 실패(재시도 가치), TIMEOUT = 재시도 금지(지연 2배 방지).
    private enum Outcome { OK, RETRYABLE, TIMEOUT }
    private record Attempt(Outcome outcome, IntentResult result) {}

    @Override
    public IntentResult classify(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return IntentResult.unknown(rawText);
        }
        // STUDY: Haiku CLI 도 간헐적으로 exit≠0/빈출력/파싱실패로 떨어진다(→ unknown = "명령 못 알아들음" 오응답).
        //        ClaudeApiClientImpl 과 동일하게 비-타임아웃 실패는 1회 재시도. 타임아웃은 즉시 unknown.
        for (int attempt = 1; attempt <= 2; attempt++) {
            Attempt a = attemptClassify(rawText);
            if (a.outcome() == Outcome.OK) {
                return a.result();
            }
            if (a.outcome() == Outcome.TIMEOUT) {
                break;
            }
            if (attempt == 1) {
                log.info("Haiku intent classify 일시 실패 → 1회 재시도");
            }
        }
        return IntentResult.unknown(rawText);
    }

    private Attempt attemptClassify(String rawText) {
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
                return new Attempt(Outcome.TIMEOUT, null);
            }
            if (result.exitCode() != 0) {
                log.warn("Haiku intent classifier exited with code={}", result.exitCode());
                return new Attempt(Outcome.RETRYABLE, null);
            }
            if (result.stdout() == null || result.stdout().isBlank()) {
                log.warn("Haiku intent classifier returned empty stdout");
                return new Attempt(Outcome.RETRYABLE, null);
            }
            IntentResult parsed = parseEnvelopeOrNull(result.stdout(), rawText);
            return parsed != null ? new Attempt(Outcome.OK, parsed) : new Attempt(Outcome.RETRYABLE, null);
        } catch (Exception e) {
            log.warn("Haiku intent classification attempt failed: {}", e.toString());
            return new Attempt(Outcome.RETRYABLE, null);
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

    // 파싱 실패/에러 응답이면 null 반환(호출부가 재시도 판단). 성공 시에만 결과 반환.
    private IntentResult parseEnvelopeOrNull(String stdout, String rawText) {
        try {
            JsonNode envelope = objectMapper.readTree(stdout);
            if (envelope.path("is_error").asBoolean(false)) {
                log.warn("Haiku reported is_error=true");
                return null;
            }
            String inner = envelope.path("result").asText("");
            if (inner.isBlank()) {
                log.warn("Haiku envelope has blank result");
                return null;
            }
            String stripped = stripToJsonObject(inner);
            return objectMapper.readValue(stripped, IntentResult.class);
        } catch (Exception e) {
            log.warn("Haiku JSON parse failed: {}", e.toString());
            return null;
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
