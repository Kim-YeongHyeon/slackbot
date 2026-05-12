package com.jirabot.slack.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.config.JiraWebhookProperties;
import com.jirabot.slack.config.JiraWebhookProperties.NotifyTrigger;
import com.jirabot.slack.service.JiraWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class JiraWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    private MockMvc mockMvc(JiraWebhookProperties props, JiraWebhookService service) {
        return standaloneSetup(new JiraWebhookController(props, service)).build();
    }

    @Test
    void tokenMissing_returns403() throws Exception {
        JiraWebhookService service = mock(JiraWebhookService.class);
        JiraWebhookProperties props = new JiraWebhookProperties(true, SECRET, NotifyTrigger.STATUS_AND_ASSIGNEE);

        mockMvc(props, service).perform(post("/api/jira/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).process(any());
    }

    @Test
    void tokenMismatch_returns403() throws Exception {
        JiraWebhookService service = mock(JiraWebhookService.class);
        JiraWebhookProperties props = new JiraWebhookProperties(true, SECRET, NotifyTrigger.STATUS_AND_ASSIGNEE);

        mockMvc(props, service).perform(post("/api/jira/webhook")
                        .param("token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).process(any());
    }

    @Test
    void enabledFalse_returns403_eitherWayService() throws Exception {
        JiraWebhookService service = mock(JiraWebhookService.class);
        JiraWebhookProperties props = new JiraWebhookProperties(false, SECRET, NotifyTrigger.STATUS_AND_ASSIGNEE);

        mockMvc(props, service).perform(post("/api/jira/webhook")
                        .param("token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).process(any());
    }

    @Test
    void validToken_returns200_andDelegatesToService() throws Exception {
        JiraWebhookService service = mock(JiraWebhookService.class);
        JiraWebhookProperties props = new JiraWebhookProperties(true, SECRET, NotifyTrigger.STATUS_AND_ASSIGNEE);

        mockMvc(props, service).perform(post("/api/jira/webhook")
                        .param("token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issue\":{\"key\":\"ES2-1\"}}"))
                .andExpect(status().isOk());

        verify(service, times(1)).process(any());
    }

    @Test
    void emptyConfiguredSecret_returns403() throws Exception {
        JiraWebhookService service = mock(JiraWebhookService.class);
        JiraWebhookProperties props = new JiraWebhookProperties(true, "", NotifyTrigger.STATUS_AND_ASSIGNEE);

        mockMvc(props, service).perform(post("/api/jira/webhook")
                        .param("token", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
