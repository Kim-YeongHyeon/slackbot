package com.jirabot.slack.controller;

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

import com.jirabot.slack.client.SlackNotifier;
import com.jirabot.slack.entity.FeatureRequestEntity;
import com.jirabot.slack.repository.FeatureRequestRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class FeatureRequestControllerTest {

    private final FeatureRequestRepository repository = mock(FeatureRequestRepository.class);
    private final SlackNotifier slackNotifier = mock(SlackNotifier.class);

    private FeatureRequestController controller(String notifyUser) {
        // save 가 받은 entity 를 그대로 돌려주도록 (실제 JPA save 동작 모사)
        when(repository.save(any(FeatureRequestEntity.class)))
                .thenAnswer((Answer<FeatureRequestEntity>) inv -> inv.getArgument(0));
        return new FeatureRequestController(repository, slackNotifier, notifyUser);
    }

    @Test
    void create_savesAndSendsDmToConfiguredAdmin() {
        var c = controller("U_ADMIN");

        var res = c.create(Map.of("title", "다크 모드", "content", "야간에 눈부심", "author", "sol"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(repository).save(any(FeatureRequestEntity.class));
        verify(slackNotifier).sendDirectMessage(eq("U_ADMIN"), contains("다크 모드"));
        FeatureRequestEntity saved = (FeatureRequestEntity) res.getBody();
        assertThat(saved.getTitle()).isEqualTo("다크 모드");
        assertThat(saved.isDone()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void create_blankTitle_returns400AndDoesNotSave() {
        var c = controller("U_ADMIN");

        var res = c.create(Map.of("title", "   ", "content", "x"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(repository, never()).save(any(FeatureRequestEntity.class));
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void create_notifyUserUnset_savesWithoutDm() {
        var c = controller("");

        var res = c.create(Map.of("title", "알림 없이 저장"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(repository).save(any(FeatureRequestEntity.class));
        verify(slackNotifier, never()).sendDirectMessage(anyString(), anyString());
    }

    @Test
    void create_dmFailure_isNonFatal() {
        var c = controller("U_ADMIN");
        doThrow(new RuntimeException("Slack down"))
                .when(slackNotifier).sendDirectMessage(anyString(), anyString());

        var res = c.create(Map.of("title", "DM 실패해도 저장"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(repository).save(any(FeatureRequestEntity.class));
    }

    @Test
    void create_tooLongTitle_returns400() {
        var c = controller("U_ADMIN");

        var res = c.create(Map.of("title", "긴".repeat(201)));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(repository, never()).save(any(FeatureRequestEntity.class));
    }

    @Test
    void patch_doneTrue_setsCompletedAt_andFalseClearsIt() {
        var c = controller("U_ADMIN");
        FeatureRequestEntity entity = new FeatureRequestEntity("t", "c", "a");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));

        var res = c.patch(1L, Map.of("done", true));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.isDone()).isTrue();
        assertThat(entity.getCompletedAt()).isNotNull();

        c.patch(1L, Map.of("done", false));
        assertThat(entity.isDone()).isFalse();
        assertThat(entity.getCompletedAt()).isNull();
    }

    @Test
    void patch_unknownId_returns404() {
        var c = controller("U_ADMIN");
        when(repository.findById(99L)).thenReturn(Optional.empty());

        var res = c.patch(99L, Map.of("done", true));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }
}
