package com.jirabot.slack.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.client.dto.IssueClassification;
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

    // STUDY: Jira Agile API에서 가져올 필드 목록. SP 커스텀 필드는 사이트마다 다르므로
    //        JiraProperties에서 읽어 동적으로 구성한다.
    private final String sprintFields;

    private final WebClient jiraWebClient;
    private final JiraProperties props;
    private final ObjectMapper objectMapper;

    public JiraApiClientImpl(WebClient jiraWebClient, JiraProperties props, ObjectMapper objectMapper) {
        this.jiraWebClient = jiraWebClient;
        this.props = props;
        this.objectMapper = objectMapper;
        this.sprintFields = "summary,status,assignee,issuetype,parent," + props.storyPointField() + ",created,updated";
    }

    @Override
    @Retryable(
            retryFor = JiraTransientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0))
    public JiraCreateResponse createIssue(IssueClassification classification, String reporterName,
                                          String jiraAccountId) {
        Map<String, Object> request = buildRequest(classification, reporterName, jiraAccountId);
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

    // STUDY: SP 커스텀 필드 ID가 사이트마다 다르므로 @JsonProperty 고정이 불가.
    //        Map으로 빌드하면 동적 필드명을 자유롭게 추가할 수 있다.
    private Map<String, Object> buildRequest(IssueClassification c, String reporterName,
                                             String jiraAccountId) {
        String issueTypeName = c.type() == IssueClassification.IssueType.BUG
                ? props.issueTypes().bug() : props.issueTypes().task();
        List<String> labels = List.of("slackbot", "origin-slack", "sp-" + c.storyPoint(),
                "claude-" + c.type().name().toLowerCase());

        Map<String, Object> fields = new java.util.HashMap<>(Map.of(
                "project", Map.of("key", props.projectKey()),
                "summary", c.title(),
                "issuetype", Map.of("name", issueTypeName),
                "description", buildAdfDescription(c, reporterName),
                "labels", labels,
                props.storyPointField(), (double) c.storyPoint()
        ));
        // STUDY: reporter/assignee는 Jira accountId로 지정. null이면 API 토큰 소유자가 기본값.
        if (jiraAccountId != null) {
            Map<String, String> accountRef = Map.of("accountId", jiraAccountId);
            fields.put("reporter", accountRef);
            fields.put("assignee", accountRef);
        }
        return Map.of("fields", fields);
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
            // STUDY: 프로젝트에 보드가 여러 개(scrum, kanban 등)일 수 있다.
            //        sprint는 scrum 보드에만 존재하므로 scrum 타입을 우선 선택한다.
            int boardId = -1;
            for (JsonNode board : boards) {
                if ("scrum".equals(board.path("type").asText())) {
                    boardId = board.path("id").asInt();
                    break;
                }
            }
            if (boardId == -1) {
                boardId = boards.get(0).path("id").asInt();
            }

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
                                .queryParam("fields", sprintFields)
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

    @Override
    public List<SprintIssue> getBacklogIssues() {
        // STUDY: board backlog endpoint 사용 — 보드의 JQL 필터가 그대로 적용되어 Jira UI 의
        //        백로그 뷰와 동일한 집합을 반환한다. 단순 `sprint is EMPTY` JQL 은 보드 필터를
        //        우회해 프로젝트 전체 이슈를 끌어오므로 UI 와 합계가 어긋났다.
        List<SprintIssue> result = new ArrayList<>();
        int startAt = 0;
        int maxBacklogIssues = 500;
        try {
            String boardJson = jiraWebClient.get()
                    .uri("/rest/agile/1.0/board?projectKeyOrId={key}&type=scrum", props.projectKey())
                    .retrieve().bodyToMono(String.class).block();
            JsonNode boards = objectMapper.readTree(boardJson).path("values");
            if (!boards.isArray() || boards.isEmpty()) {
                log.warn("No Scrum board found for backlog fetch project={}", props.projectKey());
                return result;
            }
            int boardId = boards.get(0).path("id").asInt();

            while (result.size() < maxBacklogIssues) {
                final int offset = startAt;
                String json = jiraWebClient.get()
                        .uri(uri -> uri.path("/rest/agile/1.0/board/{boardId}/backlog")
                                .queryParam("fields", sprintFields)
                                .queryParam("maxResults", 50)
                                .queryParam("startAt", offset)
                                .build(boardId))
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
            log.info("Backlog issues fetched from board {}: {} issues", boardId, result.size());
        } catch (Exception e) {
            log.error("Failed to get backlog issues: {}", e.toString());
        }
        return result;
    }

    private SprintIssue parseSprintIssue(JsonNode issue) {
        JsonNode f = issue.path("fields");
        JsonNode assignee = f.path("assignee");
        // STUDY: 하위 작업은 fields.parent.key 가 채워져 있다. parent 가 없는 일반 이슈는 null.
        JsonNode parent = f.path("parent");
        String parentKey = parent.isMissingNode() || parent.isNull() ? null : parent.path("key").asText(null);
        return new SprintIssue(
                issue.path("key").asText(),
                f.path("summary").asText(),
                f.path("status").path("name").asText(),
                f.path("status").path("statusCategory").path("name").asText(),
                assignee.isMissingNode() || assignee.isNull() ? null : assignee.path("displayName").asText(),
                f.path("issuetype").path("name").asText(),
                f.path(props.storyPointField()).asDouble(0),
                parentKey,
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

            // STUDY: transition name은 프로젝트마다 다르지만 (예: "Start to Work", "진행 중"),
            //        target status name은 프로젝트 내에서 일관적이다 (예: "진행 중").
            //        t.to.name으로 매칭하면 transition 이름에 의존하지 않아 범용적.
            String transitionId = null;
            for (JsonNode t : transitions) {
                if (targetStatusName.equals(t.path("to").path("name").asText())) {
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
    public String createSubTask(String parentKey, String summary, int storyPoint,
                                String jiraAccountId) {
        try {
            // STUDY: Jira sub-task 생성은 parent 필드로 상위 이슈를 지정한다.
            //        이슈 타입명은 사이트마다 다르므로 JiraProperties에서 읽는다.
            Map<String, Object> fields = new java.util.HashMap<>(Map.of(
                    "project", Map.of("key", props.projectKey()),
                    "parent", Map.of("key", parentKey),
                    "summary", summary,
                    "issuetype", Map.of("name", props.issueTypes().subtask()),
                    props.storyPointField(), (double) storyPoint
            ));
            if (jiraAccountId != null) {
                Map<String, String> accountRef = Map.of("accountId", jiraAccountId);
                fields.put("reporter", accountRef);
                fields.put("assignee", accountRef);
            }
            var body = Map.of("fields", fields);
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
    public boolean moveToActiveSprint(String issueKey) {
        try {
            // STUDY: Jira Agile API로 이슈를 활성 스프린트에 추가.
            //        POST /rest/agile/1.0/sprint/{sprintId}/issue에 이슈 키 목록 전달.
            Optional<SprintInfo> sprint = getActiveSprint();
            if (sprint.isEmpty()) {
                log.warn("No active sprint to move {} into", issueKey);
                return false;
            }
            jiraWebClient.post()
                    .uri("/rest/agile/1.0/sprint/{sprintId}/issue", sprint.get().id())
                    .bodyValue(Map.of("issues", List.of(issueKey)))
                    .retrieve().bodyToMono(Void.class).block();
            log.info("Issue {} moved to sprint '{}'", issueKey, sprint.get().name());
            return true;
        } catch (Exception e) {
            log.error("Failed to move {} to active sprint: {}", issueKey, e.toString());
            return false;
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
