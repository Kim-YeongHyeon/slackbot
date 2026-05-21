package com.jirabot.slack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jirabot.slack.filter.CachedBodyFilter;
import com.jirabot.slack.filter.SlackSignatureFilter;
import com.jirabot.slack.repository.IntentFailureRepository;
import com.jirabot.slack.repository.IssueRepository;
import com.jirabot.slack.repository.ProcessedJiraChangelogRepository;
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
    private JiraSyncService jiraSyncService;

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
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
