package com.jirabot.slack.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.IssueSearchEntry;
import com.jirabot.slack.client.process.ProcessRunner;
import com.jirabot.slack.config.ClaudeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

// STUDY: @Component로 스캔 대상 지정. 생성자 주입(단일 생성자는 @Autowired 생략 가능).
// STUDY: Anthropic HTTP API 대신 Claude Code CLI 를 서브프로세스로 실행 — 구독 재사용, API key 관리 회피.
@Component
public class ClaudeApiClientImpl implements ClaudeApiClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeApiClientImpl.class);

    // STUDY: tool_use 강제 스키마가 사라졌으므로 프롬프트에서 JSON-only 출력을 강제한다.
    // Few-shot 은 직접 JSON 오브젝트로 교체 — 모델이 같은 형식을 모방하도록 유도.
    static final String SYSTEM_PROMPT = """
            You are a Jira triage assistant. Classify a short natural-language description into one of
            {BUG, FEATURE, OTHER} and recommend a Story Point from the Fibonacci set {1, 2, 3, 5, 8}.

            Rules:
            - BUG: something is broken, behaves incorrectly, or throws errors.
            - FEATURE: a new capability, enhancement, or UX improvement is requested.
            - OTHER: docs, chores, questions, or anything that is not a bug or feature.
            - Story points reflect effort + uncertainty. 1 = trivial, 2 = small, 3 = normal, 5 = medium
              with some unknowns, 8 = large or cross-cutting.
            - title: <= 120 Korean/English characters, imperative mood.
            - summary: 1-2 concise paragraphs summarizing the problem/request.
            - An INTENT HINT may be provided above the user input.
              Use it as a strong signal but override if the text clearly contradicts it.
              For example, if hint says register_bug but the text is clearly a feature request, classify as FEATURE.

            --- Few-shot examples ---

            Input: "로그인 페이지에서 비밀번호 변경 후 500 에러 남. 브라우저 콘솔에 /auth/reset 500 찍힘"
            -> {"type":"BUG","storyPoint":2,"title":"비밀번호 변경 직후 /auth/reset 500 에러","summary":"로그인 페이지에서 비밀번호 변경 시 /auth/reset 엔드포인트가 500을 반환한다. 재현 경로가 명확하며 서버 로그 확인 필요."}

            Input: "결제 완료 후 주문 내역 페이지에서 금액이 0원으로 표시됨. 영수증 PDF 금액은 정상"
            -> {"type":"BUG","storyPoint":5,"title":"주문 내역 화면 결제 금액이 0원으로 표시","summary":"결제는 정상 처리되고 영수증 PDF 금액은 정상이지만 주문 내역 화면에서만 금액이 0원으로 표시된다. 데이터 전달 경로가 여러 단계이므로 재현/원인 조사에 시간이 필요."}

            Input: "프로필 페이지에 다크 모드 토글 추가해 주세요. 설정은 로컬스토리지에 저장되면 충분"
            -> {"type":"FEATURE","storyPoint":3,"title":"프로필 페이지 다크 모드 토글 추가","summary":"프로필 페이지에 다크 모드 토글을 추가하고 선택값을 로컬스토리지에 저장한다. 스타일 토큰만 교체하면 되며 서버 변경은 불필요."}

            Input: "슬랙봇으로 주간 이슈 리포트 자동 발송. 이슈 수, 해결률, 담당자별 집계 포함"
            -> {"type":"FEATURE","storyPoint":8,"title":"주간 이슈 리포트 슬랙 자동 발송","summary":"스케줄러로 주 1회 이슈 통계를 집계해 슬랙 채널에 리포트를 발송한다. 집계 쿼리, 포매팅, 스케줄링, 실패 알림이 필요하며 범위가 넓다."}

            Input: "README에 로컬 실행 명령어 추가 부탁"
            -> {"type":"OTHER","storyPoint":1,"title":"README 로컬 실행 명령어 추가","summary":"README에 로컬 실행에 필요한 명령어를 정리해 추가한다. 코드 변경 없음."}

            --- End of examples ---

            You MUST respond with ONLY a valid JSON object matching this exact schema:
            {"type":"BUG|FEATURE|OTHER","storyPoint":1|2|3|5|8,"title":"...","summary":"..."}
            No markdown fences. No prose. No comments. The entire response must be JSON.parse-able.
            """;

    private static final String STDIN_DELIMITER = "\n\n---\nUSER INPUT:\n";

    private final ProcessRunner processRunner;
    private final ClaudeProperties props;
    private final ObjectMapper objectMapper;

    public ClaudeApiClientImpl(ProcessRunner processRunner,
                               ClaudeProperties props,
                               ObjectMapper objectMapper) {
        this.processRunner = processRunner;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public IssueClassification classify(String rawText) {
        return classify(rawText, null);
    }

    @Override
    public IssueClassification classify(String rawText, IntentResult intentHint) {
        if (rawText == null || rawText.isBlank()) {
            return IssueClassification.fallback(rawText);
        }
        try {
            // STUDY: stdin 으로 프롬프트 전달 — argv 는 shell 이스케이프/플랫폼별 길이 제한 이슈 회피.
            //        한국어/개행/따옴표가 섞여도 byte-safe.
            List<String> command = buildCommand();

            // STUDY: intentHint 가 있으면 INTENT HINT 블록을 삽입하여 Haiku 분류 결과를 Sonnet 에게 전달한다.
            //        confidence 가 낮거나 hint 가 없으면 기존 포맷 유지.
            String stdin;
            if (intentHint != null && intentHint.intent() != null && !intentHint.intent().isBlank()) {
                stdin = SYSTEM_PROMPT + "\n\n---\nINTENT HINT: " + intentHint.intent()
                        + " (confidence: " + intentHint.confidence() + ")\n---\nUSER INPUT:\n" + rawText;
            } else {
                stdin = SYSTEM_PROMPT + STDIN_DELIMITER + rawText;
            }

            Duration timeout = Duration.ofSeconds(props.timeoutSeconds());

            ProcessRunner.Result result = processRunner.run(command, stdin, timeout);

            if (result.timedOut()) {
                log.warn("Claude CLI timed out after {}s", props.timeoutSeconds());
                return IssueClassification.fallback(rawText);
            }
            if (result.exitCode() != 0) {
                log.warn("Claude CLI exited with code={} stderr={}", result.exitCode(), truncate(result.stderr()));
                return IssueClassification.fallback(rawText);
            }
            if (result.stdout() == null || result.stdout().isBlank()) {
                log.warn("Claude CLI returned empty stdout");
                return IssueClassification.fallback(rawText);
            }
            return parseEnvelope(result.stdout(), rawText);
        } catch (Exception e) {
            log.warn("Claude classify failed, using fallback. err={}", e.toString());
            return IssueClassification.fallback(rawText);
        }
    }

    // STUDY: Sonnet 기반 의미 검색. 전체 이슈 목록을 Sonnet에게 전달하여 사용자 질문과 관련도 높은 이슈를 선별한다.
    //        프롬프트를 classpath 리소스(src/main/resources/prompts/)에서 로드하여 JAR 패키징에 포함되도록 한다.
    static final String SEARCH_PROMPT_RESOURCE = "prompts/sonnet-issue-search.md";

    @Override
    public List<String> searchIssues(String userQuery, List<IssueSearchEntry> issues) {
        if (userQuery == null || userQuery.isBlank() || issues == null || issues.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String systemPrompt = loadSearchPrompt();
            String stdin = buildSearchStdin(systemPrompt, userQuery, issues);

            // STUDY: Sonnet 모델로 호출. 기존 classify와 동일한 CLI 패턴이지만 model은 props.model() (Sonnet).
            List<String> command = buildCommand();
            Duration timeout = Duration.ofSeconds(props.timeoutSeconds());

            ProcessRunner.Result result = processRunner.run(command, stdin, timeout);

            if (result.timedOut()) {
                log.warn("Claude CLI search timed out after {}s", props.timeoutSeconds());
                return Collections.emptyList();
            }
            if (result.exitCode() != 0) {
                log.warn("Claude CLI search exited with code={} stderr={}", result.exitCode(), truncate(result.stderr()));
                return Collections.emptyList();
            }
            if (result.stdout() == null || result.stdout().isBlank()) {
                log.warn("Claude CLI search returned empty stdout");
                return Collections.emptyList();
            }
            return parseSearchResult(result.stdout());
        } catch (Exception e) {
            log.warn("Claude search failed: {}", e.toString());
            return Collections.emptyList();
        }
    }

    // STUDY: ClassPathResource로 JAR 내부 리소스를 로드. 파일시스템 경로 의존성 제거.
    //        리소스 로드 실패 시 인라인 fallback 프롬프트를 사용하여 애플리케이션이 중단되지 않도록 한다.
    private String loadSearchPrompt() {
        try (var is = new ClassPathResource(SEARCH_PROMPT_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            log.warn("Search prompt resource not found, using inline fallback: {}", e.toString());
            return "당신은 Jira 이슈 검색 어시스턴트입니다.\n"
                    + "사용자의 질문과 가장 관련 있는 이슈를 찾아 issueKey 목록을 JSON 배열로 반환하세요.\n"
                    + "최대 10개까지, 관련도 높은 순서로 반환합니다.\n"
                    + "관련 이슈가 없으면 빈 배열 []을 반환하세요.\n"
                    + "반드시 JSON 배열만 반환하세요. 예: [\"SLAC-7\", \"SLAC-15\"]";
        }
    }

    // STUDY: 패키지-프라이빗으로 테스트에서 직접 호출 가능.
    String buildSearchStdin(String systemPrompt, String userQuery, List<IssueSearchEntry> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        sb.append("[사용자 질문]\n").append(userQuery).append("\n\n");
        sb.append("[이슈 목록]\n");
        for (IssueSearchEntry entry : issues) {
            String assignee = entry.assignee() != null ? entry.assignee() : "미배정";
            sb.append(entry.issueKey()).append(" | ")
                    .append(entry.summary()).append(" | ")
                    .append(entry.status()).append(" | ")
                    .append("담당: ").append(assignee).append("\n");
            if (entry.description() != null && !entry.description().isBlank()) {
                // STUDY: description이 너무 길면 Sonnet 컨텍스트를 낭비하므로 200자로 제한.
                String desc = entry.description().length() > 200
                        ? entry.description().substring(0, 200) + "..."
                        : entry.description();
                sb.append("설명: ").append(desc).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // STUDY: Sonnet 응답은 --output-format json envelope 안에 JSON 배열이 들어 있다.
    //        envelope.result에서 배열을 추출하여 List<String>으로 파싱한다.
    List<String> parseSearchResult(String stdout) {
        try {
            JsonNode envelope = objectMapper.readTree(stdout);
            if (envelope.path("is_error").asBoolean(false)) {
                log.warn("Claude CLI search reported is_error=true");
                return Collections.emptyList();
            }
            String inner = envelope.path("result").asText("");
            if (inner.isBlank()) {
                return Collections.emptyList();
            }
            String stripped = stripToJsonArray(inner);
            return objectMapper.readValue(stripped, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Claude search result parse failed: {}", e.toString());
            return Collections.emptyList();
        }
    }

    // STUDY: Sonnet이 markdown fence나 추가 텍스트로 감싸는 경우 방어적으로 JSON 배열만 추출.
    private static String stripToJsonArray(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.strip();
        }
        if (!s.startsWith("[")) {
            int open = s.indexOf('[');
            int close = s.lastIndexOf(']');
            if (open >= 0 && close > open) {
                s = s.substring(open, close + 1);
            }
        }
        return s;
    }

    private List<String> buildCommand() {
        // STUDY: --output-format json → { type, subtype, result, is_error, ... } envelope 로 감싸져서 machine-parseable.
        // STUDY: --max-turns 1 → 모델이 단 한 번의 응답만 내놓도록 보장 (도구 반복 호출 루프 차단).
        // STUDY: --permission-mode plan → CLI 자체가 파일/쉘 도구 실행을 거부. 분류 전용 호출에 적합.
        return List.of(
                props.cliPath(), "-p",
                "--output-format", "json",
                "--permission-mode", props.permissionMode(),
                "--max-turns", String.valueOf(props.maxTurns()),
                "--model", props.model()
        );
    }

    private IssueClassification parseEnvelope(String stdout, String rawText) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(stdout);
        } catch (Exception e) {
            log.warn("Claude CLI envelope parse failed: {} head={}", e.toString(), truncate(stdout));
            return IssueClassification.fallback(rawText);
        }
        if (envelope.path("is_error").asBoolean(false)) {
            log.warn("Claude CLI reported is_error=true, envelope head={}", truncate(stdout));
            return IssueClassification.fallback(rawText);
        }
        String inner = envelope.path("result").asText("");
        if (inner == null || inner.isBlank()) {
            log.warn("Claude CLI envelope has blank result");
            return IssueClassification.fallback(rawText);
        }
        String stripped = stripToJsonObject(inner);
        try {
            return objectMapper.readValue(stripped, IssueClassification.class);
        } catch (Exception e) {
            log.warn("Claude inner JSON parse failed: {} head={}", e.toString(), truncate(stripped));
            return IssueClassification.fallback(rawText);
        }
    }

    // Defensive strip: remove ```json fences or prose wrapping the JSON object.
    private static String stripToJsonObject(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.strip();
        }
        if (!s.startsWith("{")) {
            int open = s.indexOf('{');
            int close = s.lastIndexOf('}');
            if (open >= 0 && close > open) {
                s = s.substring(open, close + 1);
            }
        }
        return s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
