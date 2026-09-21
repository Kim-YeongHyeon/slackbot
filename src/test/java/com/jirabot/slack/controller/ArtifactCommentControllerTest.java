package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.entity.ArtifactCommentEntity;
import com.jirabot.slack.repository.ArtifactCommentRepository;
import com.jirabot.slack.repository.ArtifactRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ArtifactCommentControllerTest {

    private ArtifactRepository artifactRepository;
    private ArtifactCommentRepository commentRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(ArtifactRepository.class);
        commentRepository = mock(ArtifactCommentRepository.class);
        mockMvc = standaloneSetup(
                new ArtifactCommentController(artifactRepository, commentRepository)).build();
    }

    // 실제 save 는 id 를 채워 반환 — mock 도 id 있는 엔티티를 돌려줘야 응답 직렬화가 NPE 안 난다.
    private ArtifactCommentEntity mockSavedWithId(long id) {
        ArtifactCommentEntity withId = mock(ArtifactCommentEntity.class);
        when(withId.getId()).thenReturn(id);
        when(commentRepository.save(any())).thenReturn(withId);
        return withId;
    }

    // 루트 댓글 목업 (부모 검증용) — parentId=null, 같은 artifactId.
    private ArtifactCommentEntity root(long id, long artifactId) {
        ArtifactCommentEntity c = mock(ArtifactCommentEntity.class);
        when(c.getId()).thenReturn(id);
        when(c.getArtifactId()).thenReturn(artifactId);
        when(c.getParentId()).thenReturn(null);
        return c;
    }

    // ===== 목록 =====

    @Test
    void list_missingArtifact_returns404() throws Exception {
        when(artifactRepository.existsById(9L)).thenReturn(false);
        mockMvc.perform(get("/api/artifacts/9/comments")).andExpect(status().isNotFound());
    }

    @Test
    void list_returnsOrderedComments() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        ArtifactCommentEntity a = new ArtifactCommentEntity(1L, null, "김", "첫 댓글", "인용", "p", "s");
        ArtifactCommentEntity b = new ArtifactCommentEntity(1L, null, "이", "둘째 댓글", null, null, null);
        when(commentRepository.findByArtifactIdOrderByCreatedAtAscIdAsc(1L))
                .thenReturn(List.of(a, b));

        mockMvc.perform(get("/api/artifacts/1/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value("첫 댓글"))
                .andExpect(jsonPath("$[0].quote").value("인용"))
                .andExpect(jsonPath("$[1].body").value("둘째 댓글"));
    }

    // ===== 루트 생성 =====

    @Test
    void create_root_savesAllAnchorFields() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        mockSavedWithId(10L);

        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"좋은 지적\",\"author\":\"김영현\","
                                + "\"quote\":\"인용문\",\"prefix\":\"앞\",\"suffix\":\"뒤\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ArtifactCommentEntity> cap = ArgumentCaptor.forClass(ArtifactCommentEntity.class);
        verify(commentRepository).save(cap.capture());
        ArtifactCommentEntity saved = cap.getValue();
        assertThat(saved.getArtifactId()).isEqualTo(1L);
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getBody()).isEqualTo("좋은 지적");
        assertThat(saved.getAuthor()).isEqualTo("김영현");
        assertThat(saved.getQuote()).isEqualTo("인용문");
        assertThat(saved.getPrefix()).isEqualTo("앞");
        assertThat(saved.getSuffix()).isEqualTo("뒤");
    }

    @Test
    void create_missingArtifact_returns404() throws Exception {
        when(artifactRepository.existsById(9L)).thenReturn(false);
        mockMvc.perform(post("/api/artifacts/9/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\"}"))
                .andExpect(status().isNotFound());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void create_missingBody_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quote\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void create_bodyTooLong_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        String big = "a".repeat(4001);
        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + big + "\"}"))
                .andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void create_quoteTooLong_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        String big = "q".repeat(501);
        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\",\"quote\":\"" + big + "\"}"))
                .andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void create_prefixTooLong_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        String big = "p".repeat(65);
        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\",\"prefix\":\"" + big + "\"}"))
                .andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());
    }

    // ===== 대댓글 =====

    @Test
    void create_reply_anchorFieldsNull_evenWhenSent() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        ArtifactCommentEntity parent = root(5L, 1L);
        when(commentRepository.findById(5L)).thenReturn(Optional.of(parent));
        mockSavedWithId(11L);

        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        // 대댓글에 앵커를 보내도 무시하고 null 저장해야 한다
                        .content("{\"body\":\"동의합니다\",\"parentId\":5,"
                                + "\"quote\":\"무시됨\",\"prefix\":\"무시\",\"suffix\":\"무시\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ArtifactCommentEntity> cap = ArgumentCaptor.forClass(ArtifactCommentEntity.class);
        verify(commentRepository).save(cap.capture());
        ArtifactCommentEntity saved = cap.getValue();
        assertThat(saved.getParentId()).isEqualTo(5L);
        assertThat(saved.getBody()).isEqualTo("동의합니다");
        assertThat(saved.getQuote()).isNull();
        assertThat(saved.getPrefix()).isNull();
        assertThat(saved.getSuffix()).isNull();
    }

    @Test
    void create_replyParentFromOtherArtifact_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        // 부모가 다른 아티팩트(2번)에 속함 → 400
        ArtifactCommentEntity parent = root(5L, 2L);
        when(commentRepository.findById(5L)).thenReturn(Optional.of(parent));

        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\",\"parentId\":5}"))
                .andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void create_replyToReply_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        // 부모가 이미 대댓글(parentId != null) → 1단계 초과라 400
        ArtifactCommentEntity reply = mock(ArtifactCommentEntity.class);
        when(reply.getArtifactId()).thenReturn(1L);
        when(reply.getParentId()).thenReturn(5L);
        when(commentRepository.findById(6L)).thenReturn(Optional.of(reply));

        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\",\"parentId\":6}"))
                .andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void create_replyParentMissing_rejected() throws Exception {
        when(artifactRepository.existsById(1L)).thenReturn(true);
        when(commentRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/artifacts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"hi\",\"parentId\":99}"))
                .andExpect(status().isBadRequest());
        verify(commentRepository, never()).save(any());
    }

    // ===== PATCH (완료/되돌리기) =====

    @Test
    void patch_resolveRoot_ok() throws Exception {
        ArtifactCommentEntity c = new ArtifactCommentEntity(1L, null, "김", "본문", "q", "p", "s");
        when(commentRepository.findById(10L)).thenReturn(Optional.of(c));

        mockMvc.perform(patch("/api/artifacts/1/comments/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));
        assertThat(c.isResolved()).isTrue();
        verify(commentRepository).save(c);
    }

    @Test
    void patch_unresolveRoot_ok() throws Exception {
        ArtifactCommentEntity c = new ArtifactCommentEntity(1L, null, "김", "본문", "q", "p", "s");
        c.setResolved(true);
        when(commentRepository.findById(10L)).thenReturn(Optional.of(c));

        mockMvc.perform(patch("/api/artifacts/1/comments/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(false));
        assertThat(c.isResolved()).isFalse();
    }

    @Test
    void patch_reply_rejected() throws Exception {
        // 대댓글에는 완료를 설정할 수 없다 → 400
        ArtifactCommentEntity reply = new ArtifactCommentEntity(1L, 5L, "김", "답글", null, null, null);
        when(commentRepository.findById(11L)).thenReturn(Optional.of(reply));

        mockMvc.perform(patch("/api/artifacts/1/comments/11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(commentRepository, never()).save(any());
    }

    @Test
    void patch_missing_returns404() throws Exception {
        when(commentRepository.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(patch("/api/artifacts/1/comments/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patch_artifactIdMismatch_returns404() throws Exception {
        // 댓글은 존재하지만 다른 아티팩트 소속 → 404
        ArtifactCommentEntity c = new ArtifactCommentEntity(2L, null, "김", "본문", null, null, null);
        when(commentRepository.findById(10L)).thenReturn(Optional.of(c));

        mockMvc.perform(patch("/api/artifacts/1/comments/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolved\":true}"))
                .andExpect(status().isNotFound());
        verify(commentRepository, never()).save(any());
    }

    // ===== DELETE =====

    @Test
    void delete_ok() throws Exception {
        ArtifactCommentEntity c = new ArtifactCommentEntity(1L, null, "김", "본문", null, null, null);
        when(commentRepository.findById(10L)).thenReturn(Optional.of(c));

        mockMvc.perform(delete("/api/artifacts/1/comments/10")).andExpect(status().isOk());
        verify(commentRepository).deleteById(10L);
    }

    @Test
    void delete_missing_returns404() throws Exception {
        when(commentRepository.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(delete("/api/artifacts/1/comments/99")).andExpect(status().isNotFound());
        verify(commentRepository, never()).deleteById(any());
    }

    @Test
    void delete_artifactIdMismatch_returns404() throws Exception {
        ArtifactCommentEntity c = new ArtifactCommentEntity(2L, null, "김", "본문", null, null, null);
        when(commentRepository.findById(10L)).thenReturn(Optional.of(c));

        mockMvc.perform(delete("/api/artifacts/1/comments/10")).andExpect(status().isNotFound());
        verify(commentRepository, never()).deleteById(any());
    }
}
