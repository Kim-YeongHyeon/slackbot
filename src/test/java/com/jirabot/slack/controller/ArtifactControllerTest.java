package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.entity.ArtifactEntity;
import com.jirabot.slack.repository.ArtifactRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ArtifactControllerTest {

    private ArtifactRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(ArtifactRepository.class);
        mockMvc = standaloneSetup(new ArtifactController(repository)).build();
    }

    private static ArtifactEntity saved(String title, String html) {
        return new ArtifactEntity(title, "김영현", html);
    }

    @Test
    void list_returnsSummariesWithoutHtml() throws Exception {
        // 목록은 projection 만 — 수 MB html 이 응답에 섞이면 탭 로드가 통째로 느려진다.
        // Mockito mock 대신 실제 구현체: mock 프록시는 내부 필드 때문에 Jackson 직렬화가 깨져
        // 응답 형태를 검증할 수 없다 (Spring Data 프록시는 인터페이스 getter 만 노출).
        when(repository.findAllSummaries()).thenReturn(List.of(new ArtifactRepository.ArtifactSummary() {
            public Long getId() { return 1L; }
            public String getTitle() { return "주간 리포트"; }
            public String getAuthor() { return "김영현"; }
            public Instant getCreatedAt() { return Instant.parse("2026-06-12T00:00:00Z"); }
            public int getSizeBytes() { return 2048; }
        }));

        mockMvc.perform(get("/api/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("주간 리포트"))
                .andExpect(jsonPath("$[0].sizeBytes").value(2048))
                .andExpect(jsonPath("$[0].html").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("html"));
        // findAll() 을 쓰면 html 이 딸려온다 — projection 전용 메서드만 호출해야 한다.
        verify(repository, never()).findAll();
    }

    @Test
    void create_savesAndReturnsIdTitle() throws Exception {
        // 실제 JPA save 는 id 를 채워 반환 — mock 도 id 있는 엔티티를 돌려줘야 한다 (없으면 응답 NPE).
        ArtifactEntity withId = mock(ArtifactEntity.class);
        when(withId.getId()).thenReturn(1L);
        when(repository.save(any())).thenReturn(withId);

        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"리포트\",\"html\":\"<html></html>\",\"author\":\"김영현\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("리포트"));

        ArgumentCaptor<ArtifactEntity> cap = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getHtml()).isEqualTo("<html></html>");
    }

    @Test
    void create_blankHtml_rejected() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"html\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void create_over5MB_rejected() throws Exception {
        String big = "a".repeat(5 * 1024 * 1024 + 1);
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"" + big + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(repository, never()).save(any());
    }

    @Test
    void resolveTitle_fallbackChain() {
        // 명시 제목 우선
        assertThat(ArtifactController.resolveTitle("명시", "<title>태그</title>", "file")).isEqualTo("명시");
        // 없으면 <title> 태그
        assertThat(ArtifactController.resolveTitle(null, "<html><title>주간 리포트</title></html>", "f"))
                .isEqualTo("주간 리포트");
        assertThat(ArtifactController.resolveTitle("", "<TITLE lang=\"ko\">대문자</TITLE>", null))
                .isEqualTo("대문자");
        // 태그도 없으면 파일명 → 최후엔 기본값
        assertThat(ArtifactController.resolveTitle(null, "<html></html>", "report")).isEqualTo("report");
        assertThat(ArtifactController.resolveTitle(null, "<html></html>", null)).isEqualTo("제목 없는 아티팩트");
    }

    @Test
    void view_servesHtmlWithCspSandbox() throws Exception {
        // XSS 방어 트립와이어: 뷰어는 반드시 CSP sandbox 로 서빙 — 이 헤더가 빠지면
        // 업로드된 HTML 이 same-origin 으로 대시보드 API 를 호출할 수 있다.
        when(repository.findById(7L)).thenReturn(Optional.of(saved("t", "<html><body>hi</body></html>")));

        mockMvc.perform(get("/artifacts/view/7"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("hi"));
    }

    @Test
    void view_notFound_returns404Html() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/artifacts/view/99"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("찾을 수 없습니다"));
    }

    @Test
    void delete_existing_deletes() throws Exception {
        when(repository.existsById(3L)).thenReturn(true);

        mockMvc.perform(delete("/api/artifacts/3")).andExpect(status().isOk());
        verify(repository).deleteById(3L);
    }

    @Test
    void delete_missing_returns404() throws Exception {
        when(repository.existsById(4L)).thenReturn(false);

        mockMvc.perform(delete("/api/artifacts/4")).andExpect(status().isNotFound());
        verify(repository, never()).deleteById(any());
    }
}
