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

    // 한 번의 시도 결과.
    private enum Outcome { OK, RETRYABLE, TIMEOUT }
    private record Attempt(Outcome outcome, IntentResult result) {}

    // STUDY: Haiku 응답 지연은 변동이 크다(6~24s 관측). 타임아웃 outlier 도 보통 일시적이라 재시도가 빠르게
    //        성공하는 경우가 많다. 분류 정확도가 응답 즉시성보다 중요하므로 최대 3회까지 재시도한다.
    //        타임아웃 후 재시도는 짧은 타임아웃으로 걸어 최악 누적 지연을 제한한다.
    static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_TIMEOUT_SECONDS = 15;

    @Override
    public IntentResult classify(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return IntentResult.unknown(rawText);
        }
        // STUDY: Haiku CLI 가 간헐적으로 exit≠0/빈출력/파싱실패/타임아웃으로 떨어진다(→ unknown = "명령 못 알아들음").
        long timeout = props.timeoutSeconds();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Attempt a = attemptClassify(rawText, timeout);
            if (a.outcome() == Outcome.OK) {
                return a.result();
            }
            // 타임아웃 이후 재시도는 짧은 타임아웃으로(outlier 는 보통 일시적이라 다음 호출은 빠름).
            if (a.outcome() == Outcome.TIMEOUT) {
                timeout = RETRY_TIMEOUT_SECONDS;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.info("Haiku intent classify {} (attempt {}/{}) → 재시도(timeout {}s)",
                        a.outcome(), attempt, MAX_ATTEMPTS, timeout);
            }
        }
        log.warn("Haiku intent classify {}회 모두 실패 → unknown", MAX_ATTEMPTS);
        return IntentResult.unknown(rawText);
    }

    private Attempt attemptClassify(String rawText, long timeoutSeconds) {
        try {
            List<String> command = buildCommand();
            Duration timeout = Duration.ofSeconds(timeoutSeconds);
            // STUDY: stdin에는 사용자 메시지만 전달. 시스템 프롬프트는 --system-prompt-file로 분리.
            //        매우 짧은 입력 ("안녕하세요", "ok") 을 모델이 시스템에 대한 직접 인사로 받아
            //        비-JSON 대화 응답을 내는 회귀가 있어, 분류 대상임을 명시적으로 프레이밍한다.
            String framedInput = "Classify this user message: " + rawText;
            ProcessRunner.Result result = processRunner.run(command, framedInput, timeout);

            if (result.timedOut()) {
                log.warn("Haiku intent classifier timed out after {}s", timeoutSeconds);
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
        // STUDY: LEAN_FLAGS — 도구 스키마/CLAUDE.md/동적 섹션 제거 + 세션 기록 중단 (ClaudeCliFlags 참고).
        //        시스템 프롬프트 ~22k→~3.2k 토큰. CLAUDE.md 지침이 분류 컨텍스트에 섞이던 간섭도 제거.
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of(
                props.cliPath(), "-p",
                "--system-prompt-file", props.promptFile(),
                "--output-format", "json",
                "--max-turns", "1",
                "--model", props.model()
        ));
        cmd.addAll(com.jirabot.slack.client.process.ClaudeCliFlags.LEAN_FLAGS);
        return cmd;
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
