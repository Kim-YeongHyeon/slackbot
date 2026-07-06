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
        this.sprintFields = "summary,status,assignee,reporter,issuetype,parent," + props.storyPointField() + ",created,updated,resolutiondate,statuscategorychangedate";
    }

    @Override
    @Retryable(
            retryFor = JiraTransientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0))
    public JiraCreateResponse createIssue(IssueClassification classification, String reporterName,
                                          String jiraAccountId) {
        return createIssue(classification, reporterName, jiraAccountId, null);
    }

    @Override
    @Retryable(retryFor = JiraTransientException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0))
    public JiraCreateResponse createIssue(IssueClassification classification, String reporterName,
                                          String jiraAccountId, String parentKey) {
        Map<String, Object> request = buildRequest(classification, reporterName, jiraAccountId, parentKey);
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
            log.info("Jira issue created key={} reporter={} parent={}", resp.key(), reporterName, parentKey);
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
                                             String jiraAccountId, String parentKey) {
        // STUDY: 이슈 타입명은 분류에 따라 매핑. EPIC 은 키워드 트리거로만 강제되는 특이 케이스.
        String issueTypeName = switch (c.type()) {
            case BUG -> props.issueTypes().bug();
            case EPIC -> props.issueTypes().epic();
            default -> props.issueTypes().task();
        };
        boolean isEpic = c.type() == IssueClassification.IssueType.EPIC;

        List<String> labels = new java.util.ArrayList<>(List.of(
                "slackbot", "origin-slack", "claude-" + c.type().name().toLowerCase()));

        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("project", Map.of("key", props.projectKey()));
        fields.put("summary", c.title());
        fields.put("issuetype", Map.of("name", issueTypeName));
        fields.put("description", buildAdfDescription(c, reporterName));
        // STUDY: 에픽은 컨테이너성 이슈라 Story Point 를 부여하지 않는다. 일부 Jira 설정은
        //        에픽에 SP 커스텀 필드 설정 자체를 거부하므로 필드/라벨 모두 생략한다.
        if (!isEpic) {
            labels.add("sp-" + c.storyPoint());
            fields.put(props.storyPointField(), (double) c.storyPoint());
        }
        fields.put("labels", labels);

        // STUDY: reporter/assignee는 Jira accountId로 지정. null이면 API 토큰 소유자가 기본값.
        if (jiraAccountId != null) {
            Map<String, String> accountRef = Map.of("accountId", jiraAccountId);
            fields.put("reporter", accountRef);
            fields.put("assignee", accountRef);
        }
        // STUDY: 상위 에픽 연결 — 이 사이트는 fields.parent.key 로 task→epic 을 잇는다(실측). 에픽 자신엔 안 붙인다.
        if (parentKey != null && !parentKey.isBlank() && !isEpic) {
            fields.put("parent", Map.of("key", parentKey));
        }
        return Map.of("fields", fields);
    }

    @Override
    public java.util.Optional<String> findEpicKeyByName(String epicName) {
        // issuetype=Epic 은 영문 정식명이라 JQL 매칭됨(L7).
        return findKeyByName(epicName,
                "project = " + props.projectKey() + " AND issuetype = Epic ORDER BY created DESC");
    }

    @Override
    public java.util.Optional<String> findIssueKeyByName(String name) {
        // STUDY: 하위작업 부모 후보 — 에픽은 제외(하위작업은 에픽 직속이 될 수 없음). issuetype != Epic 은
        //        영문 정식명(L7). 서브태스크 부모 여부는 호출부(getIssue().subtask())에서 재확인하므로
        //        JQL 에는 존재하지 않을 수 있는 Sub-task 타입명을 넣지 않는다(오타 시 JQL 400 방지).
        return findKeyByName(name,
                "project = " + props.projectKey() + " AND issuetype != Epic ORDER BY created DESC");
    }

    // STUDY: 요약(summary)으로 이슈 키를 찾는 공용 로직. 정확 일치 우선, 없으면 양방향 부분 일치.
    //        양방향: 추출 구절이 요약을 포함(넓게 잡힌 경우)하거나 그 반대.
    private java.util.Optional<String> findKeyByName(String name, String jql) {
        if (name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        String needle = name.strip().toLowerCase();
        String partial = null;
        for (SprintIssue e : searchByJql(jql)) {
            String s = e.summary() == null ? "" : e.summary().strip();
            if (s.equalsIgnoreCase(name.strip())) {
                return java.util.Optional.of(e.key());
            }
            String sl = s.toLowerCase();
            if (partial == null && !sl.isEmpty() && (sl.contains(needle) || needle.contains(sl))) {
                partial = e.key();
            }
        }
        return java.util.Optional.ofNullable(partial);
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

    // STUDY: 이슈 링크 타입은 사이트 설정이라 거의 안 변함 → 캐시. self-invocation 주의(외부 빈에서 호출돼야 캐시 탐).
    @Override
    @org.springframework.cache.annotation.Cacheable(com.jirabot.slack.config.CacheConfig.ISSUE_LINK_TYPES_CACHE)
    public List<com.jirabot.slack.client.dto.IssueLinkType> getIssueLinkTypes() {
        List<com.jirabot.slack.client.dto.IssueLinkType> result = new ArrayList<>();
        try {
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/issueLinkType")
                    .retrieve().bodyToMono(String.class).block();
            JsonNode types = objectMapper.readTree(json).path("issueLinkTypes");
            for (JsonNode t : types) {
                result.add(new com.jirabot.slack.client.dto.IssueLinkType(
                        t.path("id").asText(null),
                        t.path("name").asText(null),
                        t.path("inward").asText(null),
                        t.path("outward").asText(null)));
            }
        } catch (Exception e) {
            log.error("Failed to fetch issue link types: {}", e.toString());
        }
        return result;
    }

    // STUDY: 링크 방향 — {inwardIssue <inward> outwardIssue}. Blocks 는 outwardIssue 가 inwardIssue 를 막는다.
    //        POST /rest/api/3/issueLink 는 201(No Content) 반환. 실패 시 false.
    @Override
    public boolean linkIssues(String inwardKey, String outwardKey, String linkTypeName) {
        try {
            var body = Map.of(
                    "type", Map.of("name", linkTypeName),
                    "inwardIssue", Map.of("key", inwardKey),
                    "outwardIssue", Map.of("key", outwardKey));
            jiraWebClient.post()
                    .uri("/rest/api/3/issueLink")
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();
            log.info("Linked {} <-{}- {} (inward<-type-outward)", inwardKey, linkTypeName, outwardKey);
            return true;
        } catch (Exception e) {
            log.error("Failed to link {} and {} ({}): {}", inwardKey, outwardKey, linkTypeName, e.toString());
            return false;
        }
    }

    // STUDY: @Cacheable — 호출마다 Jira 왕복 2회(보드+스프린트)를 5분 TTL 캐시(CacheConfig)로 흡수.
    //        스프린트는 2주 주기로 바뀌므로 5분 staleness 는 무해. Optional 자체가 캐시되므로
    //        "활성 스프린트 없음"(empty) 도 5분간 재조회하지 않는다.
    //        주의: 같은 클래스 내부 호출(self-invocation)은 프록시를 우회해 캐시를 타지 않는다.
    @Override
    @org.springframework.cache.annotation.Cacheable(com.jirabot.slack.config.CacheConfig.ACTIVE_SPRINT_CACHE)
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
        JsonNode reporter = f.path("reporter");
        // STUDY: 하위 작업은 fields.parent.key 가 채워져 있다. parent 가 없는 일반 이슈는 null.
        JsonNode parent = f.path("parent");
        String parentKey = parent.isMissingNode() || parent.isNull() ? null : parent.path("key").asText(null);
        return new SprintIssue(
                issue.path("key").asText(),
                f.path("summary").asText(),
                f.path("status").path("name").asText(),
                f.path("status").path("statusCategory").path("name").asText(),
                assignee.isMissingNode() || assignee.isNull() ? null : assignee.path("displayName").asText(),
                reporter.isMissingNode() || reporter.isNull() ? null : reporter.path("displayName").asText(),
                f.path("issuetype").path("name").asText(),
                f.path("issuetype").path("subtask").asBoolean(false),
                f.path(props.storyPointField()).asDouble(0),
                parentKey,
                f.path("created").asText(""),
                f.path("updated").asText(""),
                // STUDY: 이 사이트는 done 이슈에 resolutiondate 를 안 채우는 경우가 많아,
                //        "완료 시각" 으로 statuscategorychangedate(Done 카테고리 진입 시각)를 폴백으로 쓴다.
                firstNonBlankDate(f.path("resolutiondate"), f.path("statuscategorychangedate")));
    }

    private static String firstNonBlankDate(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && !n.isMissingNode() && !n.isNull()) {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    @Override
    public boolean issueExists(String issueKey) {
        try {
            jiraWebClient.get()
                    .uri("/rest/api/3/issue/{key}?fields=key", issueKey)
                    .retrieve().bodyToMono(String.class).block();
            return true;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;  // 삭제됨
            }
            // 불확실(권한/5xx 등) → 오삭제 방지 위해 존재로 간주.
            log.warn("issueExists {} unexpected {} — treating as exists", issueKey, e.getStatusCode());
            return true;
        } catch (Exception e) {
            log.warn("issueExists {} error: {} — treating as exists", issueKey, e.toString());
            return true;
        }
    }

    @Override
    public Optional<SprintIssue> getIssue(String issueKey) {
        try {
            // STUDY: sync 와 동일한 필드 집합으로 단건 조회 — parseSprintIssue 를 그대로 재사용한다.
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/issue/{key}?fields={fields}", issueKey, sprintFields)
                    .retrieve().bodyToMono(String.class).block();
            return Optional.of(parseSprintIssue(objectMapper.readTree(json)));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.info("getIssue {}: not found (404)", issueKey);
            } else {
                log.warn("getIssue {} failed: {}", issueKey, e.getStatusCode());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("getIssue {} error: {}", issueKey, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean assignIssue(String issueKey, String accountId) {
        try {
            // STUDY: PUT /issue/{key}/assignee — body 의 accountId 가 null 이면 담당자 해제.
            //        성공 시 204 No Content (본문 없음) → bodyToMono(Void.class) 대신 toBodilessEntity.
            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("accountId", accountId);
            jiraWebClient.put()
                    .uri("/rest/api/3/issue/{key}/assignee", issueKey)
                    .bodyValue(body)
                    .retrieve().toBodilessEntity().block();
            log.info("Assigned {} to accountId={}", issueKey, accountId);
            return true;
        } catch (WebClientResponseException e) {
            log.warn("assignIssue {} failed: {} body={}", issueKey, e.getStatusCode(),
                    e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("assignIssue {} error: {}", issueKey, e.toString());
            return false;
        }
    }

    @Override
    public boolean transitionIssue(String issueKey, String targetStatusName) {
        try {
            // STUDY: Jira 상태 전환은 2단계 — (1) 가능한 transition 목록 조회 (2) transition 실행.
            //        transition ID는 프로젝트/워크플로마다 다르므로 동적으로 조회해야 한다.
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/issue/{key}/transitions", issueKey)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = objectMapper.readTree(json);
            JsonNode transitions = root.path("transitions");

            // STUDY: transition API가 에러를 반환하면 transitions 키가 없어 빈 노드가 된다.
            //        이 경우 로그에 응답 본문을 남겨 원인 파악을 돕는다.
            if (transitions.isMissingNode() || !transitions.isArray()) {
                log.warn("Transition API returned no transitions for {}: {}", issueKey, json);
                return false;
            }

            // STUDY: transition name은 프로젝트마다 다르지만 (예: "Start to Work", "진행 중"),
            //        target status name은 프로젝트 내에서 일관적이다 (예: "진행 중").
            //        t.to.name으로 매칭하면 transition 이름에 의존하지 않아 범용적.
            String transitionId = null;
            for (JsonNode t : transitions) {
                String toName = t.path("to").path("name").asText();
                log.debug("Available transition: id={} name='{}' to.name='{}'",
                        t.path("id").asText(), t.path("name").asText(), toName);
                if (targetStatusName.equals(toName)) {
                    transitionId = t.path("id").asText();
                    break;
                }
            }
            if (transitionId == null) {
                log.warn("Transition '{}' not found for issue {}. Available: {}",
                        targetStatusName, issueKey, transitions);
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
        // STUDY: 에픽은 SP 가 없으므로 푸터에 SP 를 표기하지 않는다.
        String classifiedText = c.type() == IssueClassification.IssueType.EPIC
                ? "Classified as EPIC"
                : "Classified as " + c.type() + " · Story Point " + c.storyPoint();
        return Map.of(
                "version", 1,
                "type", "doc",
                "content", List.of(
                        Map.of("type", "paragraph", "content", List.of(
                                Map.of("type", "text", "text", reporterText))),
                        Map.of("type", "paragraph", "content", List.of(
                                Map.of("type", "text", "text", c.summary() == null ? "" : c.summary()))),
                        Map.of("type", "paragraph", "content", List.of(
                                Map.of("type", "text", "text", classifiedText)))));
    }

    // STUDY: 새 Jira Cloud 검색 엔드포인트 /rest/api/3/search/jql 사용(구 /search 는 deprecated).
    //        nextPageToken 기반 페이지네이션. guard 로 무한 루프 방지(최대 50페이지).
    @Override
    public List<SprintIssue> searchByJql(String jql) {
        List<SprintIssue> result = new ArrayList<>();
        String nextToken = null;
        int guard = 0;
        try {
            do {
                final String token = nextToken;
                String json = jiraWebClient.get()
                        .uri(uri -> {
                            uri.path("/rest/api/3/search/jql")
                                    .queryParam("jql", jql)
                                    .queryParam("fields", sprintFields)
                                    .queryParam("maxResults", 100);
                            if (token != null) {
                                uri.queryParam("nextPageToken", token);
                            }
                            return uri.build();
                        })
                        .retrieve().bodyToMono(String.class).block();
                JsonNode root = objectMapper.readTree(json);
                for (JsonNode issue : root.path("issues")) {
                    result.add(parseSprintIssue(issue));
                }
                JsonNode tokenNode = root.path("nextPageToken");
                nextToken = (tokenNode.isMissingNode() || tokenNode.isNull()) ? null : tokenNode.asText(null);
                guard++;
            } while (nextToken != null && guard < 50);
            log.info("JQL search returned {} issues for '{}'", result.size(), jql);
        } catch (Exception e) {
            log.error("JQL search failed '{}': {}", jql, e.toString());
        }
        return result;
    }

    @Override
    public List<String> getComments(String issueKey) {
        List<String> out = new ArrayList<>();
        try {
            String json = jiraWebClient.get()
                    .uri("/rest/api/3/issue/{key}/comment?maxResults=50&orderBy=created", issueKey)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode comments = objectMapper.readTree(json).path("comments");
            for (JsonNode c : comments) {
                String text = extractAdfText(c.path("body"));
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch comments for {}: {}", issueKey, e.toString());
        }
        return out;
    }

    // STUDY: Atlassian Document Format(ADF) 은 중첩 content 트리. text 노드만 재귀 수집해 평문화한다.
    private static String extractAdfText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        collectAdfText(node, sb);
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    private static void collectAdfText(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        JsonNode text = node.path("text");
        if (text.isTextual()) {
            sb.append(text.asText()).append(' ');
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                collectAdfText(child, sb);
            }
        }
    }
}
