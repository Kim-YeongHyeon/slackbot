package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.filter.SlackSignatureFilter;
import com.jirabot.slack.repository.FeatureRequestRepository;
import com.jirabot.slack.repository.GitHubUserMappingRepository;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ProcessedJiraChangelogRepository;
import com.jirabot.slack.repository.ResponseMetricRepository;
import com.jirabot.slack.repository.UserMappingRepository;
import com.jirabot.slack.service.JiraSyncService;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "slack.signing-secret=test-signing-secret",
        // StartupEnvValidator(필수 키 fail-fast) 통과용 더미 — 이 테스트는 test 프로필을 안 쓰므로 직접 주입.
        "slack.bot-token=xoxb-test",
        "jira.base-url=http://localhost:9999",
        "jira.email=test@example.com",
        "jira.api-token=test-token",
        "jira.project-key=TEST",
        // v0.0.73: 대시보드 전면 로그인 — validator 필수 키 + httpBasic 계정
        "dashboard.user=sol",
        "dashboard.password=test-pw",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueRepository issueRepository;

    @MockitoBean
    private IntentFailureRepository intentFailureRepository;

    @MockitoBean
    private UserMappingRepository userMappingRepository;

    @MockitoBean
    private ProcessedJiraChangelogRepository processedJiraChangelogRepository;

    @MockitoBean
    private ResponseMetricRepository responseMetricRepository;

    @MockitoBean
    private FeatureRequestRepository featureRequestRepository;

    @MockitoBean
    private GitHubUserMappingRepository gitHubUserMappingRepository;

    // L11: 새 JPA repo 는 여기 명시적으로 mock 해야 컨텍스트가 뜬다 (v0.0.71 artifacts).
    @MockitoBean
    private com.jirabot.slack.repository.ArtifactRepository artifactRepository;

    // L11 (v0.0.72 artifact assets)
    @MockitoBean
    private com.jirabot.slack.repository.ArtifactAssetRepository artifactAssetRepository;

    // L11 (v0.0.75 artifact comments)
    @MockitoBean
    private com.jirabot.slack.repository.ArtifactCommentRepository artifactCommentRepository;

    @MockitoBean
    private JiraSyncService jiraSyncService;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    // v0.0.73: 대시보드 전면 로그인 필수 — 터널이 아닌 :8080 직접 접근도 401 이어야 한다.
    @Test
    void dashboardPathsRequireLogin() throws Exception {
        mockMvc.perform(get("/dashboard/")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/artifacts")).andExpect(status().isUnauthorized());
        // 뷰어도 로그인 대상 (v0.0.73 — 무인증 링크 공유 중단)
        mockMvc.perform(get("/artifacts/view/1/")).andExpect(status().isUnauthorized());
        // v0.0.75: 인라인 댓글 리뷰 페이지·댓글 API 도 로그인 필수.
        // 리뷰 진입점은 302 리다이렉트지만, 무인증이면 리다이렉트 전에 401 이어야 한다.
        mockMvc.perform(get("/artifacts/review/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/artifacts/1/comments")).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardPathsAcceptBasicAuth() throws Exception {
        org.mockito.Mockito.when(artifactRepository.findAllSummaries())
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/artifacts").header("Authorization", basic("sol", "test-pw")))
                .andExpect(status().isOk());
    }

    @Test
    void reviewStaticPage_servedWithAuth() throws Exception {
        // {id:\d+} 회귀 방지 — 컨트롤러 매핑이 index.html 을 삼키면 400 이 나며 리뷰 SPA 가 깨진다.
        mockMvc.perform(get("/artifacts/review/index.html?id=1")
                        .header("Authorization", basic("sol", "test-pw")))
                .andExpect(status().isOk());
    }

    @Test
    void artifactViewer_framableBySameOrigin() throws Exception {
        // v0.0.75 라이브 검증에서 발견: XFO 라이터는 컨트롤러가 넣은 헤더를 무조건 덮어쓴다.
        // 전역 sameOrigin 설정이 빠지면 리뷰 iframe·카드 미리보기가 전부 차단되므로 회귀 고정.
        mockMvc.perform(get("/artifacts/view/1/").header("Authorization", basic("sol", "test-pw")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(get("/api/artifacts").header("Authorization", basic("sol", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthStaysPublic() throws Exception {
        // start.sh / jdk-watchdog.sh / 봇상태 카드가 자격증명 없이 호출 — 로그인 대상에서 제외 유지.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private static String basic(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void slackEndpointWithInvalidSignatureReturns403() throws Exception {
        mockMvc.perform(post("/api/slack/event")
                        .header("X-Slack-Request-Timestamp", "1700000000")
                        .header("X-Slack-Signature", "v0=deadbeef")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void slackEndpointWithoutHeadersReturns403() throws Exception {
        mockMvc.perform(post("/api/slack/event")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unmappedPathIsDenied() throws Exception {
        mockMvc.perform(get("/nonexistent"))
                .andExpect(status().is4xxClientError());
    }

    // STUDY: SlackSignatureFilter는 CachedBodyFilter가 캐시한 raw body로 HMAC을 검증한다.
    //        등록 순서가 뒤집히면 stream이 이미 소비되어 검증이 빈 body로 통과되거나 실패하므로,
    //        chain 순서를 invariant로 강제하는 회귀 방지 테스트를 둔다.
    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Test
    void cachedBodyFilterRunsBeforeSlackSignatureFilter() {
        List<Filter> filters = securityFilterChain.getFilters();
        int cachedIdx = indexOf(filters, CachedBodyFilter.class);
        int signatureIdx = indexOf(filters, SlackSignatureFilter.class);
        int upaIdx = indexOf(filters, UsernamePasswordAuthenticationFilter.class);

        assertThat(cachedIdx)
                .as("CachedBodyFilter must be registered in the security chain")
                .isGreaterThanOrEqualTo(0);
        assertThat(signatureIdx)
                .as("SlackSignatureFilter must be registered in the security chain")
                .isGreaterThanOrEqualTo(0);
        assertThat(cachedIdx)
                .as("CachedBodyFilter must precede SlackSignatureFilter")
                .isLessThan(signatureIdx);
        if (upaIdx >= 0) {
            assertThat(signatureIdx)
                    .as("SlackSignatureFilter must precede UsernamePasswordAuthenticationFilter")
                    .isLessThan(upaIdx);
        }
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
