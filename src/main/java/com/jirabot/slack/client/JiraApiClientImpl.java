package com.jirabot.slack.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateRequest;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.client.dto.SprintInfo;
import com.jirabot.slack.client.dto.SprintIssue;
import com.jirabot.slack.config.JiraProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

// STUDY: @Retryable은 AOP 프록시로 동작 — 같은 빈 내부 호출은 재시도가 안 걸린다. 외부 빈에서 호출해야 함.
// STUDY: retryFor/noRetryFor로 어떤 예외만 재시도할지 명시.
@Component
public class JiraApiClientImpl implements JiraApiClient {

    private static final Logger log = LoggerFactory.getLogger(JiraApiClientImpl.class);

    // STUDY: Jira Agile REST API의 보드 ID. application.yml에서 설정 가능하도록 확장 가능하지만
    //        현재 SLAC 프로젝트 전용이므로 프로퍼티로 관리.
    private static final String SPRINT_FIELDS = "summary,status,assignee,issuetype,customfield_10016,created,updated";

    private final WebClient jiraWebClient;
    private final JiraProperties props;
    private final ObjectMapper objectMapper;

    public JiraApiClientImpl(WebClient jiraWebClient, JiraProperties props, ObjectMapper objectMapper) {
        this.jiraWebClient = jiraWebClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    @Retryable(
            retryFor = JiraTransientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0))
    public JiraCreateResponse createIssue(IssueClassification classification, String reporterName,
                                          String jiraAccountId) {
        JiraCreateRequest request = buildRequest(classification, reporterName, jiraAccountId);
        try {
            JiraCreateResponse resp = jiraWebClient.post()
                    .uri("/rest/api/3/issue")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JiraCreateResponse.class)
                    .block();
            if (resp == null || resp.key() == null) {
                throw new JiraApiException("Jira returned empty response");
            }
            log.info("Jira issue created key={} reporter={}", resp.key(), reporterName);
            return resp;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            if (status >= 500 || status == 429) {
                throw new JiraTransientException("Jira " + status + ": " + body, e);
            }
            throw new JiraApiException("Jira " + status + ": " + body, e);
        } catch (JiraApiException | JiraTransientException e) {
            throw e;
        } catch (Exception e) {
            throw new JiraTransientException("Jira call failed: " + e.getMessage(), e);
        }
    }

    private JiraCreateRequest buildRequest(IssueClassification c, String reporterName,
                                          String jiraAccountId) {
        // Jira 사이트 언어가 한국어로 생성돼 이슈 타입 name 이 "버그"/"작업" 으로 저장됨.
        // Team-managed 프로젝트 타입은 REST API 리네임 불가 → 매핑으로 처리.
        String issueTypeName = c.type() == IssueClassification.IssueType.BUG ? "버그" : "작업";
        List<String> labels = List.of("slackbot", "origin-slack", "sp-" + c.storyPoint(),
                "claude-" + c.type().name().toLowerCase());

        // STUDY: reporter/assignee는 Jira accountId로 지정. null이면 API 토큰 소유자가 기본값.
        JiraCreateRequest.AccountRef accountRef = jiraAccountId != null
                ? new JiraCreateRequest.AccountRef(jiraAccountId) : null;

        return new JiraCreateRequest(new JiraCreateRequest.Fields(
                new JiraCreateRequest.ProjectRef(props.projectKey()),
                c.title(),
                new JiraCreateRequest.IssueTypeRef(issueTypeName),
                buildAdfDescription(c, reporterName),
                labels,
                (double) c.storyPoint(),
                accountRef,
                accountRef));
    }

    @Override
    public String findAccountId(String displayName) {
        try {
            // STUDY: Jira user search API로 displayName 검색 → accountId 반환.
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/user/search?query={name}", displayName)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode users = objectMapper.readTree(json);
            if (users.isArray() && !users.isEmpty()) {
                String accountId = users.get(0).path("accountId").asText(null);
                log.debug("Jira accountId for '{}': {}", displayName, accountId);
                return accountId;
            }
            log.warn("No Jira user found for '{}'", displayName);
            return null;
        } catch (Exception e) {
            log.warn("Failed to search Jira user '{}': {}", displayName, e.toString());
            return null;
        }
    }

    @Override
    public Optional<SprintInfo> getActiveSprint() {
        try {
            // STUDY: Jira Agile API로 프로젝트의 보드를 찾고, 활성 스프린트를 조회한다.
            //        보드 ID는 프로젝트마다 다르므로 동적으로 조회.
            String boardJson = jiraWebClient.get()
                    .uri("/rest/agile/1.0/board?projectKeyOrId={key}", props.projectKey())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode boards = objectMapper.readTree(boardJson).path("values");
            if (!boards.isArray() || boards.isEmpty()) {
                log.warn("No board found for project {}", props.projectKey());
                return Optional.empty();
            }
            int boardId = boards.get(0).path("id").asInt();

            String sprintJson = jiraWebClient.get()
                    .uri("/rest/agile/1.0/board/{boardId}/sprint?state=active", boardId)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode sprints = objectMapper.readTree(sprintJson).path("values");
            if (!sprints.isArray() || sprints.isEmpty()) {
                return Optional.empty();
            }
            JsonNode s = sprints.get(0);
            return Optional.of(new SprintInfo(
                    s.path("id").asInt(),
                    s.path("name").asText(),
                    s.path("state").asText(),
                    s.path("startDate").asText(""),
                    s.path("endDate").asText("")));
        } catch (Exception e) {
            log.error("Failed to get active sprint: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<SprintIssue> getSprintIssues(int sprintId) {
        List<SprintIssue> result = new ArrayList<>();
        int startAt = 0;
        try {
            while (true) {
                final int offset = startAt;
                String json = jiraWebClient.get()
                        .uri(uri -> uri.path("/rest/agile/1.0/sprint/{sprintId}/issue")
                                .queryParam("fields", SPRINT_FIELDS)
                                .queryParam("maxResults", 50)
                                .queryParam("startAt", offset)
                                .build(sprintId))
                        .retrieve().bodyToMono(String.class).block();
                JsonNode root = objectMapper.readTree(json);
                JsonNode issues = root.path("issues");
                for (JsonNode issue : issues) {
                    result.add(parseSprintIssue(issue));
                }
                int total = root.path("total").asInt();
                startAt += issues.size();
                if (startAt >= total) break;
            }
        } catch (Exception e) {
            log.error("Failed to get sprint issues: {}", e.toString());
        }
        return result;
    }

    private SprintIssue parseSprintIssue(JsonNode issue) {
        JsonNode f = issue.path("fields");
        JsonNode assignee = f.path("assignee");
        return new SprintIssue(
                issue.path("key").asText(),
                f.path("summary").asText(),
                f.path("status").path("name").asText(),
                f.path("status").path("statusCategory").path("name").asText(),
                assignee.isMissingNode() || assignee.isNull() ? null : assignee.path("displayName").asText(),
                f.path("issuetype").path("name").asText(),
                f.path("customfield_10016").asDouble(0),
                f.path("created").asText(""),
                f.path("updated").asText(""));
    }

    @Override
    public boolean transitionIssue(String issueKey, String targetStatusName) {
        try {
            // STUDY: Jira 상태 전환은 2단계 — (1) 가능한 transition 목록 조회 (2) transition 실행.
            //        transition ID는 프로젝트/워크플로마다 다르므로 동적으로 조회해야 한다.
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/issue/{key}/transitions", issueKey)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode transitions = objectMapper.readTree(json).path("transitions");

            String transitionId = null;
            for (JsonNode t : transitions) {
                if (targetStatusName.equals(t.path("name").asText())) {
                    transitionId = t.path("id").asText();
                    break;
                }
            }
            if (transitionId == null) {
                log.warn("Transition '{}' not found for issue {}", targetStatusName, issueKey);
                return false;
            }

            jiraWebClient.post()
                    .uri("/rest/api/3/issue/{key}/transitions", issueKey)
                    .bodyValue(Map.of("transition", Map.of("id", transitionId)))
                    .retrieve().bodyToMono(Void.class).block();

            log.info("Issue {} transitioned to '{}'", issueKey, targetStatusName);
            return true;
        } catch (Exception e) {
            log.error("Failed to transition issue {}: {}", issueKey, e.toString());
            return false;
        }
    }

    @Override
    public String createSubTask(String parentKey, String summary, int storyPoint) {
        try {
            // STUDY: Jira sub-task 생성은 parent 필드로 상위 이슈를 지정한다.
            //        Team-managed 프로젝트의 하위 작업 타입은 "하위 작업" (한글).
            var body = Map.of("fields", Map.of(
                    "project", Map.of("key", props.projectKey()),
                    "parent", Map.of("key", parentKey),
                    "summary", summary,
                    "issuetype", Map.of("name", "하위 작업"),
                    "customfield_10016", (double) storyPoint
            ));
            String json = jiraWebClient.post()
                    .uri("/rest/api/3/issue")
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode resp = objectMapper.readTree(json);
            String key = resp.path("key").asText();
            log.info("Sub-task created key={} parent={}", key, parentKey);
            return key;
        } catch (Exception e) {
            log.error("Failed to create sub-task for {}: {}", parentKey, e.toString());
            throw new JiraApiException("Sub-task creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void addComment(String issueKey, String commentText) {
        try {
            // STUDY: Jira v3 comment body는 ADF 형식. 최소 paragraph 구조로 전달.
            var body = Map.of("body", Map.of(
                    "version", 1,
                    "type", "doc",
                    "content", List.of(
                            Map.of("type", "paragraph", "content", List.of(
                                    Map.of("type", "text", "text", commentText)
                            ))
                    )
            ));
            jiraWebClient.post()
                    .uri("/rest/api/3/issue/{key}/comment", issueKey)
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();
            log.info("Comment added to {}", issueKey);
        } catch (Exception e) {
            log.error("Failed to add comment to {}: {}", issueKey, e.toString());
            throw new JiraApiException("Comment failed: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void appendDescription(String issueKey, String additionalText) {
        try {
            // STUDY: Jira description 수정은 기존 내용을 GET → 추가 텍스트 append → PUT.
            //        기존 ADF content 배열에 새 paragraph를 추가하는 방식.
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/issue/{key}?fields=description", issueKey)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode current = objectMapper.readTree(json);
            JsonNode desc = current.path("fields").path("description");

            // 기존 content 배열 복사 + 새 paragraph 추가
            var contentList = new ArrayList<Map<String, Object>>();
            if (desc.has("content")) {
                for (JsonNode node : desc.path("content")) {
                    contentList.add(objectMapper.convertValue(node, Map.class));
                }
            }
            contentList.add(Map.of("type", "paragraph", "content", List.of(
                    Map.of("type", "text", "text", "\n--- 추가 내용 (Slack) ---\n" + additionalText)
            )));

            var body = Map.of("fields", Map.of(
                    "description", Map.of(
                            "version", 1,
                            "type", "doc",
                            "content", contentList
                    )
            ));
            jiraWebClient.put()
                    .uri("/rest/api/3/issue/{key}", issueKey)
                    .bodyValue(body)
                    .retrieve().bodyToMono(Void.class).block();
            log.info("Description appended to {}", issueKey);
        } catch (Exception e) {
            log.error("Failed to append description to {}: {}", issueKey, e.toString());
            throw new JiraApiException("Description update failed: " + e.getMessage(), e);
        }
    }

    // STUDY: Jira v3 description은 ADF(Atlassian Document Format) JSON. 최소 구조로 paragraph + codeBlock.
    private Map<String, Object> buildAdfDescription(IssueClassification c, String reporter) {
        String reporterText = "Reported by @" + (reporter == null ? "unknown" : reporter) + " via Slack";
        return Map.of(
                "version", 1,
                "type", "doc",
                "content", List.of(
                        Map.of("type", "paragraph", "content", List.of(
                                Map.of("type", "text", "text", reporterText))),
                        Map.of("type", "paragraph", "content", List.of(
                                Map.of("type", "text", "text", c.summary() == null ? "" : c.summary()))),
                        Map.of("type", "paragraph", "content", List.of(
                                Map.of("type", "text", "text",
                                        "Classified as " + c.type() + " · Story Point " + c.storyPoint())))));
    }
}
