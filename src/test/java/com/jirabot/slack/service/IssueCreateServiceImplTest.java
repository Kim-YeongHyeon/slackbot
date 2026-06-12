package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.JiraApiException;
import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.client.dto.IntentResult;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.dto.IssueCreateCommand;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.ResponseMetricEntity;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ResponseMetricRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.DuplicateDetectionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IssueCreateServiceImplTest {

    private final ClaudeApiClient claude = mock(ClaudeApiClient.class);
    private final JiraApiClient jira = mock(JiraApiClient.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);
    private final DuplicateDetectionService duplicateDetection = mock(DuplicateDetectionService.class);
    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final UserMappingRepository userMappingRepository = mock(UserMappingRepository.class);
    private final ResponseMetricRepository responseMetricRepository = mock(ResponseMetricRepository.class);
    private final JiraProperties jiraProps = new JiraProperties(
            "https://example.atlassian.net", "u@x.com", "token", "PROJ", null, null);
    private final IssueCreateServiceImpl service =
            new IssueCreateServiceImpl(claude, jira, jiraProps, slackNotifier, duplicateDetection,
                    issueRepository, userMappingRepository, responseMetricRepository);

    @Test
    void happyPath_createsIssueAndReturnsUrl() throws ExecutionException, InterruptedException {
        // Registered user mapping must exist for the guard clause
        when(userMappingRepository.findBySlackUserId("U123"))
                .thenReturn(Optional.of(new UserMappingEntity("U123", "Kim", "김영현")));

        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 2, "title", "summary");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), anyString(), any()))
                .thenReturn(new JiraCreateResponse("10001", "PROJ-1", "https://..."));

        var cmd = new IssueCreateCommand("login broken", "U123", "C1", "123.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isTrue();
        assertThat(result.issueKey()).isEqualTo("PROJ-1");
        assertThat(result.issueUrl()).isEqualTo("https://example.atlassian.net/browse/PROJ-1");
        verify(claude).classify(eq("login broken"), any());
        verify(issueRepository).save(any());
        verify(slackNotifier).postBlockMessage(eq("C1"), eq("123.0"), any(), any());
    }

    @Test
    void jiraFailure_returnsFailureResult() throws Exception {
        when(userMappingRepository.findBySlackUserId("U1"))
                .thenReturn(Optional.of(new UserMappingEntity("U1", "User", "유저")));
        when(claude.classify(anyString(), any()))
                .thenReturn(IssueClassification.fallback("x"));
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(any(), anyString(), any())).thenThrow(new JiraApiException("400 bad"));

        var cmd = new IssueCreateCommand("x", "U1", "C", "0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("400");
    }

    @Test
    void unregisteredUser_notifiesAndReturnsFailure() throws Exception {
        // No mapping exists for this user
        when(userMappingRepository.findBySlackUserId("U_NEW"))
                .thenReturn(Optional.empty());

        var cmd = new IssueCreateCommand("deploy failed", "U_NEW", "C1", "111.0");
        var result = service.createFromSlackText(cmd).get();

        // Should fail with "unregistered"
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("unregistered");

        // Should send registration guidance via thread reply
        verify(slackNotifier).postThreadReply(eq("C1"), eq("111.0"), contains("등록"));

        // Should NOT call Claude classify or Jira API
        verify(claude, never()).classify(anyString(), any());
        verify(jira, never()).createIssue(any(), anyString(), any());
        verify(issueRepository, never()).save(any());
    }

    @Test
    void unregisteredUser_noChannelInfo_skipsNotification() throws Exception {
        when(userMappingRepository.findBySlackUserId("U_NEW"))
                .thenReturn(Optional.empty());

        var cmd = new IssueCreateCommand("deploy failed", "U_NEW", null, null);
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("unregistered");

        // No notification when channel/eventTs are null
        verify(slackNotifier, never()).postThreadReply(anyString(), anyString(), anyString());
        verify(claude, never()).classify(anyString(), any());
    }

    @Test
    void unregisteredUser_notificationFails_returnsUnregisteredNotRuntimeException() throws Exception {
        when(userMappingRepository.findBySlackUserId("U_FAIL"))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("Slack API down"))
                .when(slackNotifier).postThreadReply(anyString(), anyString(), anyString());

        var cmd = new IssueCreateCommand("deploy failed", "U_FAIL", "C1", "111.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("unregistered");

        // Should NOT call Claude classify or Jira API
        verify(claude, never()).classify(anyString(), any());
        verify(jira, never()).createIssue(any(), anyString(), any());
    }

    @Test
    void registerEpicIntent_forcesEpicTypeAndZeroStoryPoint() throws Exception {
        when(userMappingRepository.findBySlackUserId("U_E"))
                .thenReturn(Optional.of(new UserMappingEntity("U_E", "Kim", "김영현")));

        // Sonnet 이 FEATURE/SP5 로 분류해도 register_epic 의도면 EPIC/SP0 으로 강제되어야 한다.
        var sonnet = new IssueClassification(
                IssueClassification.IssueType.FEATURE, 5, "GCP 배포 확장", "요약");
        when(claude.classify(anyString(), any())).thenReturn(sonnet);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(any(), anyString(), any()))
                .thenReturn(new JiraCreateResponse("10009", "PROJ-9", "https://..."));

        var intent = new IntentResult("register_epic", 1.0, Map.of(), "에픽 GCP 배포 확장");
        var cmd = new IssueCreateCommand("에픽 GCP 배포 확장", "U_E", "C1", "1.0");
        var result = service.createFromSlackText(cmd, intent).get();

        assertThat(result.success()).isTrue();
        ArgumentCaptor<IssueClassification> cap = ArgumentCaptor.forClass(IssueClassification.class);
        verify(jira).createIssue(cap.capture(), anyString(), any());
        assertThat(cap.getValue().type()).isEqualTo(IssueClassification.IssueType.EPIC);
        assertThat(cap.getValue().storyPoint()).isZero();
        assertThat(cap.getValue().title()).isEqualTo("GCP 배포 확장");
    }

    @Test
    void savedEntity_reporterIsJiraDisplayName_notSlackUserId() throws Exception {
        // IssueEntity.reporter 계약: Jira displayName. Slack ID 가 저장되면
        // webhook DM 의 resolveMention 이 매핑 조회에 실패해 raw ID 가 노출된다.
        when(userMappingRepository.findBySlackUserId("U03L1TJ0EBB"))
                .thenReturn(Optional.of(new UserMappingEntity("U03L1TJ0EBB", "Kim", "YeongHyeonKim")));

        var classification = new IssueClassification(
                IssueClassification.IssueType.FEATURE, 2, "audit log 확인", "요약");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), anyString(), any()))
                .thenReturn(new JiraCreateResponse("10003", "PROJ-3", "https://..."));

        var cmd = new IssueCreateCommand("audit log 확인", "U03L1TJ0EBB", "C1", "1.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isTrue();
        ArgumentCaptor<IssueEntity> cap = ArgumentCaptor.forClass(IssueEntity.class);
        verify(issueRepository).save(cap.capture());
        assertThat(cap.getValue().getReporter()).isEqualTo("YeongHyeonKim");
        assertThat(cap.getValue().getReporter()).isNotEqualTo("U03L1TJ0EBB");
    }

    @Test
    void successPath_recordsResponseMetricWithStageTimings() throws Exception {
        when(userMappingRepository.findBySlackUserId("U1"))
                .thenReturn(Optional.of(new UserMappingEntity("U1", "Kim", "김영현")));
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 2, "title", "summary");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), anyString(), any()))
                .thenReturn(new JiraCreateResponse("10001", "PROJ-1", "https://..."));

        var result = service.createFromSlackText(new IssueCreateCommand("x", "U1", "C1", "1.0")).get();

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ResponseMetricEntity> cap = ArgumentCaptor.forClass(ResponseMetricEntity.class);
        verify(responseMetricRepository).save(cap.capture());
        ResponseMetricEntity metric = cap.getValue();
        assertThat(metric.getAction()).isEqualTo("issue_create");
        assertThat(metric.getIssueKey()).isEqualTo("PROJ-1");
        assertThat(metric.isSuccess()).isTrue();
        // eventTs "1.0"(1970년) 기준이므로 total 은 항상 양수이며 단계 시간이 모두 채워진다
        assertThat(metric.getTotalMs()).isPositive();
        assertThat(metric.getClassifyMs()).isNotNull();
        assertThat(metric.getDuplicateMs()).isNotNull();
        assertThat(metric.getJiraMs()).isNotNull();
        assertThat(metric.getDbMs()).isNotNull();
        assertThat(metric.getNotifyMs()).isNotNull();
        assertThat(metric.getErrorType()).isNull();
    }

    @Test
    void jiraFailure_recordsFailureMetricWithErrorType() throws Exception {
        when(userMappingRepository.findBySlackUserId("U1"))
                .thenReturn(Optional.of(new UserMappingEntity("U1", "User", "유저")));
        when(claude.classify(anyString(), any())).thenReturn(IssueClassification.fallback("x"));
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(any(), anyString(), any())).thenThrow(new JiraApiException("400 bad"));

        var result = service.createFromSlackText(new IssueCreateCommand("x", "U1", "C", "0")).get();

        assertThat(result.success()).isFalse();
        ArgumentCaptor<ResponseMetricEntity> cap = ArgumentCaptor.forClass(ResponseMetricEntity.class);
        verify(responseMetricRepository).save(cap.capture());
        ResponseMetricEntity metric = cap.getValue();
        assertThat(metric.isSuccess()).isFalse();
        assertThat(metric.getErrorType()).isEqualTo("JiraApiException");
        assertThat(metric.getIssueKey()).isNull();
        // Jira 단계에서 죽었으므로 분류/중복 시간은 있고 jira 이후는 비어 있다
        assertThat(metric.getClassifyMs()).isNotNull();
        assertThat(metric.getDuplicateMs()).isNotNull();
        assertThat(metric.getJiraMs()).isNull();
    }

    @Test
    void metricSaveFailure_doesNotBreakIssueCreation() throws Exception {
        when(userMappingRepository.findBySlackUserId("U1"))
                .thenReturn(Optional.of(new UserMappingEntity("U1", "Kim", "김영현")));
        var classification = new IssueClassification(
                IssueClassification.IssueType.BUG, 2, "title", "summary");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), anyString(), any()))
                .thenReturn(new JiraCreateResponse("10001", "PROJ-1", "https://..."));
        doThrow(new RuntimeException("DB down"))
                .when(responseMetricRepository).save(any(ResponseMetricEntity.class));

        var result = service.createFromSlackText(new IssueCreateCommand("x", "U1", "C1", "1.0")).get();

        // 계측 실패는 non-fatal — 이슈 생성 결과에 영향이 없어야 한다
        assertThat(result.success()).isTrue();
        assertThat(result.issueKey()).isEqualTo("PROJ-1");
    }

    @Test
    void registeredUser_issueCreatedNormally() throws Exception {
        // Verify that a registered user goes through the full creation flow
        var mappingEntity = new UserMappingEntity("U_REG", "Registered", "등록된사용자");
        when(userMappingRepository.findBySlackUserId("U_REG"))
                .thenReturn(Optional.of(mappingEntity));

        var classification = new IssueClassification(
                IssueClassification.IssueType.FEATURE, 3, "새 기능 추가", "새 기능 요약");
        when(claude.classify(anyString(), any())).thenReturn(classification);
        when(duplicateDetection.findSimilar(anyString())).thenReturn(List.of());
        when(jira.createIssue(eq(classification), eq("등록된사용자"), any()))
                .thenReturn(new JiraCreateResponse("10002", "PROJ-2", "https://..."));

        var cmd = new IssueCreateCommand("새 기능 추가해주세요", "U_REG", "C2", "222.0");
        var result = service.createFromSlackText(cmd).get();

        assertThat(result.success()).isTrue();
        assertThat(result.issueKey()).isEqualTo("PROJ-2");

        // Verify full flow executed
        verify(claude).classify(eq("새 기능 추가해주세요"), any());
        verify(jira).createIssue(eq(classification), eq("등록된사용자"), any());
        verify(issueRepository).save(any());
    }
}
