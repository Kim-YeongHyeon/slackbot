package com.jirabot.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jirabot.slack.client.ClaudeApiClient;
import com.jirabot.slack.client.GitHubApiClient;
import com.jirabot.slack.client.JiraApiClient;
import com.jirabot.slack.client.dto.IssueClassification;
import com.jirabot.slack.client.dto.JiraCreateResponse;
import com.jirabot.slack.client.dto.PullRequestDetail;
import com.jirabot.slack.config.JiraProperties;
import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrImportServiceImplTest {

    private GitHubApiClient gitHub;
    private ClaudeApiClient claude;
    private JiraApiClient jira;
    private IssueRepository issueRepository;
    private UserMappingRepository userMappingRepository;
    private PrImportServiceImpl service;

    @BeforeEach
    void setUp() {
        gitHub = mock(GitHubApiClient.class);
        claude = mock(ClaudeApiClient.class);
        jira = mock(JiraApiClient.class);
        issueRepository = mock(IssueRepository.class);
        userMappingRepository = mock(UserMappingRepository.class);
        service = new PrImportServiceImpl(gitHub, claude, jira, issueRepository, userMappingRepository,
                new JiraProperties("https://j.example.com", "u@x", "t", "ES2", null, null));
    }

    // --- 영업일 계산 ---

    @Test
    void businessDays_excludesWeekend() {
        // 금 17:00 KST → 월 17:00 KST. 토/일 제외 → 금 7h + 월 17h = 24h = 1.0 영업일.
        Instant fri17 = Instant.parse("2026-06-12T08:00:00Z");   // 2026-06-12 17:00 KST (금)
        Instant mon17 = Instant.parse("2026-06-15T08:00:00Z");   // 2026-06-15 17:00 KST (월)
        assertThat(PrImportServiceImpl.businessDaysBetween(fri17, mon17)).isCloseTo(1.0, within(0.01));
    }

    @Test
    void businessDays_sameWeekdayShortSpan() {
        Instant a = Instant.parse("2026-06-15T01:00:00Z"); // 월 10:00 KST
        Instant b = Instant.parse("2026-06-15T07:00:00Z"); // 월 16:00 KST (6h)
        assertThat(PrImportServiceImpl.businessDaysBetween(a, b)).isCloseTo(0.25, within(0.01));
    }

    @Test
    void businessDays_nonPositiveSpanIsZero() {
        Instant a = Instant.parse("2026-06-15T08:00:00Z");
        assertThat(PrImportServiceImpl.businessDaysBetween(a, a)).isZero();
        assertThat(PrImportServiceImpl.businessDaysBetween(a, null)).isZero();
    }

    @Test
    void storyPointMapping_boundaries() {
        assertThat(PrImportServiceImpl.storyPointForBusinessDays(0.5)).isEqualTo(1);
        assertThat(PrImportServiceImpl.storyPointForBusinessDays(0.51)).isEqualTo(2);
        assertThat(PrImportServiceImpl.storyPointForBusinessDays(1.0)).isEqualTo(2);
        assertThat(PrImportServiceImpl.storyPointForBusinessDays(2.0)).isEqualTo(3);
        assertThat(PrImportServiceImpl.storyPointForBusinessDays(3.0)).isEqualTo(5);
        assertThat(PrImportServiceImpl.storyPointForBusinessDays(3.1)).isEqualTo(8);
    }

    // --- 오케스트레이션 ---

    private PullRequestDetail mergedPr() {
        return new PullRequestDetail(7, "decryptor token 누락 수정", "원인: 세션 만료. 핸들러 추가.",
                "https://github.com/CryptoLabInc/evi/pull/7", "alice", true,
                Instant.parse("2026-06-12T08:00:00Z"), Instant.parse("2026-06-15T08:00:00Z")); // 1 영업일
    }

    @Test
    void importMergedPr_happyPath_createsTransitionsAndPersists() {
        when(gitHub.getPullRequest("CryptoLabInc", "evi", 7)).thenReturn(Optional.of(mergedPr()));
        // PR 작성자(alice) → GitHub name → Jira accountId 해결.
        when(gitHub.getUserDisplayName("alice")).thenReturn(Optional.of("Suyeong Park"));
        when(jira.findAccountId("Suyeong Park")).thenReturn("acc-suyeong");
        when(claude.classify(anyString())).thenReturn(new IssueClassification(
                IssueClassification.IssueType.BUG, 99, "decryptor 토큰 누락", "세션 만료 미처리"));
        when(jira.createIssue(any(), any(), any())).thenReturn(new JiraCreateResponse("100", "ES2-300", "self"));
        when(jira.transitionIssue(anyString(), anyString())).thenReturn(true);

        var r = service.importMergedPr("https://github.com/CryptoLabInc/evi/pull/7", "U1");

        assertThat(r.success()).isTrue();
        assertThat(r.issueKey()).isEqualTo("ES2-300");
        assertThat(r.issueUrl()).isEqualTo("https://j.example.com/browse/ES2-300");
        assertThat(r.storyPoint()).isEqualTo(2);    // 1 영업일 → SP 2 (PR 텍스트의 99 는 무시)
        assertThat(r.finalStatus()).isEqualTo("완료");
        assertThat(r.assignee()).isEqualTo("Suyeong Park");

        // reporter/assignee = PR 작성자 accountId 로 createIssue 호출.
        ArgumentCaptor<IssueClassification> cc = ArgumentCaptor.forClass(IssueClassification.class);
        verify(jira).createIssue(cc.capture(), eq("Suyeong Park"), eq("acc-suyeong"));
        assertThat(cc.getValue().storyPoint()).isEqualTo(2);

        // 전체 워크플로 전환 + 스프린트 이동.
        verify(jira).transitionIssue("ES2-300", "해야 할 일");
        verify(jira).transitionIssue("ES2-300", "진행 중");
        verify(jira).transitionIssue("ES2-300", "검토 중");
        verify(jira).transitionIssue("ES2-300", "완료");
        verify(jira).moveToActiveSprint("ES2-300");

        // 로컬 DB 적재 — 완료 시각 = merge 시각.
        ArgumentCaptor<IssueEntity> ec = ArgumentCaptor.forClass(IssueEntity.class);
        verify(issueRepository).save(ec.capture());
        assertThat(ec.getValue().getCompletedAt()).isEqualTo(Instant.parse("2026-06-15T08:00:00Z"));
        assertThat(ec.getValue().getJiraCreated()).isEqualTo(Instant.parse("2026-06-12T08:00:00Z"));
    }

    @Test
    void importMergedPr_authorUnresolved_fallsBackToInvoker() {
        when(gitHub.getPullRequest(anyString(), anyString(), eq(7))).thenReturn(Optional.of(mergedPr()));
        when(gitHub.getUserDisplayName("alice")).thenReturn(Optional.of("alice")); // name 없음 → login
        when(jira.findAccountId(anyString())).thenReturn(null);                    // Jira 매칭 실패
        when(userMappingRepository.findBySlackUserId("U1")).thenReturn(Optional.of(
                new com.jirabot.slack.entity.UserMappingEntity("U1", "Kim", "김영현", "acc-kim")));
        when(claude.classify(anyString())).thenReturn(new IssueClassification(
                IssueClassification.IssueType.OTHER, 1, "t", "s"));
        when(jira.createIssue(any(), any(), any())).thenReturn(new JiraCreateResponse("1", "ES2-301", "self"));
        when(jira.transitionIssue(anyString(), anyString())).thenReturn(true);

        var r = service.importMergedPr("https://github.com/CryptoLabInc/evi/pull/7", "U1");

        assertThat(r.success()).isTrue();
        verify(jira).createIssue(any(), eq("김영현"), eq("acc-kim"));
        assertThat(r.assignee()).isEqualTo("김영현");
    }

    @Test
    void importMergedPr_notMerged_rejected() {
        PullRequestDetail open = new PullRequestDetail(7, "t", "b", "url", "alice", false,
                Instant.parse("2026-06-12T08:00:00Z"), null);
        when(gitHub.getPullRequest(anyString(), anyString(), eq(7))).thenReturn(Optional.of(open));

        var r = service.importMergedPr("https://github.com/CryptoLabInc/evi/pull/7", null);

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("merge");
        verify(jira, never()).createIssue(any(), any(), any());
    }

    @Test
    void importMergedPr_badUrl_rejected() {
        var r = service.importMergedPr("https://example.com/not-a-pr", null);
        assertThat(r.success()).isFalse();
        verify(gitHub, never()).getPullRequest(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void importMergedPr_prNotFound_rejected() {
        when(gitHub.getPullRequest(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        var r = service.importMergedPr("https://github.com/CryptoLabInc/evi/pull/7", null);
        assertThat(r.success()).isFalse();
        verify(jira, never()).createIssue(any(), any(), any());
    }
}
