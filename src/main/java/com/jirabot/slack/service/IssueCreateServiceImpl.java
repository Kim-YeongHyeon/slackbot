package com.jirabot.slack.service;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.dto.IssueCreateCommand;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.ResponseMetricEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ResponseMetricRepository;
import com.jirabot.slack.util.BlockKitBuilder;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// STUDY: @Async는 반드시 proxy를 거쳐 호출될 때만 동작 (self-invocation 금지).
// 컨트롤러에서 이 서비스를 주입받아 호출하므로 OK.
@Service
public class IssueCreateServiceImpl implements IssueCreateService {

    private static final Logger log = LoggerFactory.getLogger(IssueCreateServiceImpl.class);

    private final ClaudeApiClient claude;
    private final JiraApiClient jira;
    private final JiraProperties jiraProps;
    private final SlackNotifier slackNotifier;
    private final DuplicateDetectionService duplicateDetection;
    private final IssueRepository issueRepository;
    private final com.jirabot.slack.repository.UserMappingRepository userMappingRepository;
    private final ResponseMetricRepository responseMetricRepository;

    public IssueCreateServiceImpl(ClaudeApiClient claude, JiraApiClient jira,
                                  JiraProperties jiraProps, SlackNotifier slackNotifier,
                                  DuplicateDetectionService duplicateDetection,
                                  IssueRepository issueRepository,
                                  com.jirabot.slack.repository.UserMappingRepository userMappingRepository,
                                  ResponseMetricRepository responseMetricRepository) {
        this.claude = claude;
        this.jira = jira;
        this.jiraProps = jiraProps;
        this.slackNotifier = slackNotifier;
        this.duplicateDetection = duplicateDetection;
        this.issueRepository = issueRepository;
        this.userMappingRepository = userMappingRepository;
        this.responseMetricRepository = responseMetricRepository;
    }

    @Override
    public IssueClassification classifyOnly(String rawText, IntentResult intentHint) {
        return claude.classify(rawText, intentHint);
    }

    // STUDY: @Async(qualifier)로 명시 executor 지정 → security-config-engineer의 "slackTaskExecutor" pool 사용.
    // Controller는 fire-and-forget이므로 포화 시 AbortPolicy로 RejectedExecutionException 발생 → AsyncUncaughtExceptionHandler에서 warn 처리.
    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<IssueCreateResult> createFromSlackText(IssueCreateCommand command) {
        return createFromSlackText(command, null);
    }

    @Async("slackTaskExecutor")
    @Override
    public CompletableFuture<IssueCreateResult> createFromSlackText(IssueCreateCommand command, IntentResult intentHint) {
        long startNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        StageTimings timings = new StageTimings();
        try {
            // STUDY: Guard clause 패턴 — 사전 조건(Slack-Jira 매핑)이 충족되지 않으면 빠르게 실패.
            //        이전에는 매핑 없을 때 Slack displayName으로 auto-map했으나,
            //        Slack 이름 ≠ Jira 이름인 경우 잘못된 reporter로 이슈가 생성되는 문제가 있었다.
            var mapping = userMappingRepository.findBySlackUserId(command.slackUserId());
            if (mapping.isEmpty()) {
                log.info("Issue creation blocked - unregistered user={}", command.slackUserId());
                notifyRegistrationRequired(command);
                return CompletableFuture.completedFuture(IssueCreateResult.failure("unregistered"));
            }

            log.info("Classify request user={} textLen={} intentHint={}", command.slackUserId(),
                    command.rawText() == null ? 0 : command.rawText().length(),
                    intentHint != null ? intentHint.intent() : "none");
            long stage = System.nanoTime();
            IssueClassification classification = claude.classify(command.rawText(), intentHint);
            timings.classifyMs = elapsedMs(stage);
            // STUDY: 에픽은 `에픽`/`epic` 키워드로만 진입하는 특이 케이스(register_epic). Sonnet 의 BUG/FEATURE
            //        판단과 SP 추정을 무시하고 EPIC 으로 강제하여 스토리/버그와 확실히 구별한다. 제목/요약은 재사용.
            if (intentHint != null && "register_epic".equals(intentHint.intent())) {
                classification = classification.asEpic();
            }

            // 중복 감지: Jira 생성 전에 DB에서 유사 이슈 검색
            stage = System.nanoTime();
            List<IssueEntity> similar = duplicateDetection.findSimilar(classification.title());
            timings.duplicateMs = elapsedMs(stage);
            if (!similar.isEmpty()) {
                log.info("Found {} similar issues for '{}'", similar.size(), classification.title());
            }

            // STUDY: guard clause에서 이미 매핑을 조회했으므로 재사용하여 불필요한 DB 쿼리를 방지한다.
            var mappingEntity = mapping.get();
            String reporterName = mappingEntity.getJiraDisplayName();
            stage = System.nanoTime();
            String jiraAccountId = resolveJiraAccountId(mappingEntity);
            JiraCreateResponse created = jira.createIssue(classification, reporterName, jiraAccountId);
            timings.jiraMs = elapsedMs(stage);
            String url = buildIssueUrl(created.key());
            log.info("Issue created key={} url={} type={} sp={}", created.key(), url,
                    classification.type(), classification.storyPoint());
            // STUDY: IssueEntity.reporter 계약은 "Jira displayName" (webhook DM 의 resolveMention 이
            //        displayName 으로 매핑을 조회). Slack ID 를 넣으면 DM 에 raw ID 가 노출된다.
            stage = System.nanoTime();
            saveToDb(created.key(), classification, reporterName, command);
            timings.dbMs = elapsedMs(stage);
            stage = System.nanoTime();
            notifySlack(command, created.key(), url, classification, similar,
                    totalElapsedMs(command, startNanos));
            timings.notifyMs = elapsedMs(stage);
            recordMetric(command, created.key(), true, null, timings,
                    totalElapsedMs(command, startNanos), startedAt);
            return CompletableFuture.completedFuture(IssueCreateResult.ok(created.key(), url));
        } catch (Exception e) {
            log.error("Issue creation failed for user={}: {}", command.slackUserId(), e.toString(), e);
            notifyFailure(command, e);
            recordMetric(command, null, false, e.getClass().getSimpleName(), timings,
                    totalElapsedMs(command, startNanos), startedAt);
            return CompletableFuture.completedFuture(IssueCreateResult.failure(e.getMessage()));
        }
    }

    // 단계별 소요시간 누적용 가변 홀더 (성공/실패 경로 양쪽에서 기록에 사용).
    private static final class StageTimings {
        Long classifyMs;
        Long duplicateMs;
        Long jiraMs;
        Long dbMs;
        Long notifyMs;
    }

    private static long elapsedMs(long sinceNanos) {
        return (System.nanoTime() - sinceNanos) / 1_000_000;
    }

    // STUDY: Slack 메시지 ts(epoch초.마이크로초)가 있으면 사용자가 메시지를 보낸 순간부터의
    //        end-to-end 소요시간을 계산 — Go봇/터널 전달, Haiku 의도분류, async 큐 대기까지 포함.
    //        ts 가 없거나 비정상이면 이 서비스 진입 시점 기준으로 폴백.
    private static long totalElapsedMs(IssueCreateCommand command, long startNanos) {
        if (command.eventTs() != null) {
            try {
                long eventMillis = (long) (Double.parseDouble(command.eventTs()) * 1000);
                long elapsed = System.currentTimeMillis() - eventMillis;
                if (elapsed > 0) {
                    return elapsed;
                }
            } catch (NumberFormatException ignored) {
                // 폴백으로 진행
            }
        }
        return elapsedMs(startNanos);
    }

    private void recordMetric(IssueCreateCommand command, String issueKey, boolean success,
                              String errorType, StageTimings t, long totalMs, Instant startedAt) {
        try {
            responseMetricRepository.save(new ResponseMetricEntity(
                    "issue_create", issueKey, command.slackUserId(), command.channel(),
                    success, totalMs, t.classifyMs, t.duplicateMs, t.jiraMs, t.dbMs, t.notifyMs,
                    errorType, startedAt));
        } catch (Exception e) {
            // 계측 실패가 본 기능을 깨면 안 된다 — 기록만 남기고 무시.
            log.warn("Failed to record response metric (non-fatal): {}", e.toString());
        }
    }

    private void notifyFailure(IssueCreateCommand command, Exception e) {
        if (command.channel() == null || command.eventTs() == null) {
            return;
        }
        try {
            slackNotifier.postThreadReply(command.channel(), command.eventTs(),
                    ":x: 이슈 생성 중 오류가 발생했어요: " + e.getMessage());
        } catch (Exception notifyEx) {
            log.warn("Failure notification to Slack also failed: {}", notifyEx.toString());
        }
    }

    private void notifySlack(IssueCreateCommand command, String key, String url,
                             IssueClassification classification, List<IssueEntity> similar,
                             long elapsedMs) {
        if (command.channel() == null || command.eventTs() == null) {
            return;
        }
        // STUDY: Block Kit JSON으로 리치 메시지 + 액션 버튼을 전송한다.
        //        text 필드는 Block Kit 미지원 클라이언트용 fallback.
        //        에픽은 스프린트 워크플로 대상이 아니므로 SP/워크플로 버튼 없이 별도 메시지로 구별한다.
        boolean isEpic = classification.type() == IssueClassification.IssueType.EPIC;
        String fallbackText = isEpic
                ? String.format(":bookmark_tabs: Epic이 생성되었습니다! [%s] %s %s",
                        key, classification.title(), url)
                : String.format(
                        ":white_check_mark: Jira 이슈가 등록되었습니다! [%s] %s 분류: %s | SP: %d %s",
                        key, classification.title(), classification.type(),
                        classification.storyPoint(), url);

        String blocksJson = isEpic
                ? BlockKitBuilder.buildEpicCreatedBlocks(key, url, classification, similar, elapsedMs)
                : BlockKitBuilder.buildIssueCreatedBlocks(key, url, classification, similar, elapsedMs);

        slackNotifier.postBlockMessage(command.channel(), command.eventTs(), fallbackText, blocksJson);
    }

    // STUDY: resolveReporterName은 guard clause에서 매핑 엔티티를 재사용하도록 인라인화됨.
    //        createFromSlackText()에서 mappingEntity.getJiraDisplayName()으로 직접 접근.

    private void notifyRegistrationRequired(IssueCreateCommand command) {
        if (command.channel() == null || command.eventTs() == null) return;
        String message = ":warning: Jira 계정이 연결되지 않았습니다.\n"
                + "먼저 아래 명령으로 등록해주세요:\n"
                + "`@지라 등록 <Jira에 표시되는 이름>`\n"
                + "예: `@지라 등록 홍길동`\n"
                + "등록 후 다시 시도해주세요!";
        try {
            slackNotifier.postThreadReply(command.channel(), command.eventTs(), message);
        } catch (Exception e) {
            log.warn("Failed to send registration guidance to Slack: {}", e.toString());
        }
    }

    // STUDY: guard clause에서 이미 조회한 매핑 엔티티를 받아 DB 재조회를 방지한다.
    private String resolveJiraAccountId(UserMappingEntity mappingEntity) {
        try {
            // 1. 매핑에 accountId가 있으면 사용
            if (mappingEntity.getJiraAccountId() != null) {
                return mappingEntity.getJiraAccountId();
            }

            // 2. Jira API로 검색하여 accountId 획득
            String displayName = mappingEntity.getJiraDisplayName();
            String accountId = jira.findAccountId(displayName);
            if (accountId != null) {
                // 매핑에 accountId 저장 (다음번에는 API 호출 없이 사용)
                mappingEntity.setJiraAccountId(accountId);
                userMappingRepository.save(mappingEntity);
                log.info("Saved Jira accountId for {}: {}", displayName, accountId);
            }
            return accountId;
        } catch (Exception e) {
            log.warn("Failed to resolve Jira accountId for {}: {}", mappingEntity.getSlackUserId(), e.toString());
            return null;
        }
    }

    private void saveToDb(String issueKey, IssueClassification c, String reporter,
                          IssueCreateCommand command) {
        try {
            String issueType = switch (c.type()) {
                case BUG -> jiraProps.issueTypes().bug();
                case EPIC -> jiraProps.issueTypes().epic();
                default -> jiraProps.issueTypes().task();
            };
            // STUDY: 새로 생성된 이슈의 초기 상태는 "Backlog". Kanban Backlog Managing에 배치됨.
            IssueEntity entity = new IssueEntity(
                    issueKey, c.title(), issueType, "Backlog", StatusCategory.TODO,
                    null, (double) c.storyPoint(), reporter, c.summary(),
                    Instant.now(), Instant.now());
            // 스레드에서 "@지라 완료" 시 이슈를 찾을 수 있도록 Slack 스레드 정보 저장
            if (command.channel() != null && command.eventTs() != null) {
                entity.setSlackThread(command.channel(), command.eventTs());
            }
            issueRepository.save(entity);
            log.debug("Issue saved to DB key={}", issueKey);
        } catch (Exception e) {
            log.warn("Failed to save issue to DB (non-fatal): {}", e.toString());
        }
    }

    private String buildIssueUrl(String key) {
        String base = jiraProps.baseUrl();
        if (base == null || base.isBlank()) {
            return key;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/browse/" + key;
    }
}
