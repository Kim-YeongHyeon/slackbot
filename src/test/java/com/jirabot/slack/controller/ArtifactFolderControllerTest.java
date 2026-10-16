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

import com.jirabot.slack.entity.ArtifactFolderEntity;
import com.jirabot.slack.repository.ArtifactFolderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ArtifactFolderControllerTest {

    private ArtifactFolderRepository repo;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repo = mock(ArtifactFolderRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc = standaloneSetup(new ArtifactFolderController(repo)).build();
    }

    private static String name(String n) {
        return "{\"name\":\"" + n + "\"}";
    }

    @Test
    void list_orderedByName() throws Exception {
        when(repo.findAllByOrderByNameAsc()).thenReturn(List.of(
                new ArtifactFolderEntity("설계"), new ArtifactFolderEntity("온보딩")));
        mockMvc.perform(get("/api/artifact-folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("설계"))
                .andExpect(jsonPath("$[1].name").value("온보딩"));
    }

    @Test
    void create_trimsName() throws Exception {
        mockMvc.perform(post("/api/artifact-folders").contentType(MediaType.APPLICATION_JSON)
                        .content(name("  설계 문서  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("설계 문서"));
    }

    @Test
    void create_blankOrTooLong_rejected() throws Exception {
        mockMvc.perform(post("/api/artifact-folders").contentType(MediaType.APPLICATION_JSON)
                        .content(name("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(post("/api/artifact-folders").contentType(MediaType.APPLICATION_JSON)
                        .content(name("가".repeat(51))))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    void create_duplicateIgnoringCase_rejected() throws Exception {
        when(repo.existsByNameIgnoreCase("Design")).thenReturn(true);
        mockMvc.perform(post("/api/artifact-folders").contentType(MediaType.APPLICATION_JSON)
                        .content(name("Design")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 있는 폴더 이름입니다: Design"));
        verify(repo, never()).save(any());
    }

    @Test
    void rename_ok_and_sameNameAsSelfAllowed() throws Exception {
        ArtifactFolderEntity f = new ArtifactFolderEntity("설계");
        when(repo.findById(3L)).thenReturn(Optional.of(f));
        when(repo.existsByNameIgnoreCaseAndIdNot("설계 v2", 3L)).thenReturn(false);
        mockMvc.perform(patch("/api/artifact-folders/3").contentType(MediaType.APPLICATION_JSON)
                        .content(name("설계 v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("설계 v2"));
        assertThat(f.getName()).isEqualTo("설계 v2");
    }

    @Test
    void rename_toOtherFoldersName_rejected() throws Exception {
        ArtifactFolderEntity f = new ArtifactFolderEntity("설계");
        when(repo.findById(3L)).thenReturn(Optional.of(f));
        when(repo.existsByNameIgnoreCaseAndIdNot("온보딩", 3L)).thenReturn(true);
        mockMvc.perform(patch("/api/artifact-folders/3").contentType(MediaType.APPLICATION_JSON)
                        .content(name("온보딩")))
                .andExpect(status().isBadRequest());
        assertThat(f.getName()).isEqualTo("설계");
    }

    @Test
    void rename_and_delete_missing_404() throws Exception {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        when(repo.existsById(9L)).thenReturn(false);
        mockMvc.perform(patch("/api/artifact-folders/9").contentType(MediaType.APPLICATION_JSON)
                        .content(name("x")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/artifact-folders/9")).andExpect(status().isNotFound());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void delete_existing() throws Exception {
        when(repo.existsById(3L)).thenReturn(true);
        mockMvc.perform(delete("/api/artifact-folders/3")).andExpect(status().isOk());
        verify(repo).deleteById(3L);   // 안의 아티팩트는 DB FK(ON DELETE SET NULL)가 미분류로
    }
}
