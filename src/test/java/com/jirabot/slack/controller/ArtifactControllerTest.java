package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import com.jirabot.slack.entity.ArtifactEntity;
import com.jirabot.slack.repository.ArtifactAssetRepository;
import com.jirabot.slack.repository.ArtifactRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class ArtifactControllerTest {

    private ArtifactRepository repository;
    private ArtifactAssetRepository assetRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(ArtifactRepository.class);
        assetRepository = mock(ArtifactAssetRepository.class);
        mockMvc = standaloneSetup(new ArtifactController(repository, assetRepository)).build();
    }

    private static ArtifactEntity saved(String title, String html) {
        return new ArtifactEntity(title, "김영현", html);
    }

    private ArtifactEntity mockSavedWithId(long id) {
        // 실제 JPA save 는 id 를 채워 반환 — mock 도 id 있는 엔티티를 돌려줘야 한다 (없으면 응답 NPE).
        ArtifactEntity withId = mock(ArtifactEntity.class);
        when(withId.getId()).thenReturn(id);
        when(repository.save(any())).thenReturn(withId);
        return withId;
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
            public long getAssetCount() { return 3; }
        }));

        mockMvc.perform(get("/api/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("주간 리포트"))
                .andExpect(jsonPath("$[0].sizeBytes").value(2048))
                .andExpect(jsonPath("$[0].assetCount").value(3))
                .andExpect(jsonPath("$[0].html").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("html"));
        // findAll() 을 쓰면 html 이 딸려온다 — projection 전용 메서드만 호출해야 한다.
        verify(repository, never()).findAll();
    }

    @Test
    void create_savesAndReturnsIdTitle() throws Exception {
        mockSavedWithId(1L);

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
    void createMultipart_savesAssetsWithServerDerivedContentType() throws Exception {
        mockSavedWithId(5L);

        mockMvc.perform(multipart("/api/artifacts")
                        // 클라이언트 part content-type 은 가짜 — 서버가 확장자로 유도해야 한다
                        .file(new MockMultipartFile("assets", "img.png", "application/x-bogus", new byte[]{1, 2}))
                        .file(new MockMultipartFile("assets", "style.css", "application/x-bogus", new byte[]{3}))
                        .param("assetPaths", "page_files/img.png")
                        .param("assetPaths", "page_files/스타일.css")
                        .param("title", "저장된 페이지")
                        .param("html", "<html><body>hi</body></html>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.assetCount").value(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArtifactAssetEntity>> cap = ArgumentCaptor.forClass(List.class);
        verify(assetRepository).saveAll(cap.capture());
        List<ArtifactAssetEntity> stored = cap.getValue();
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getArtifactId()).isEqualTo(5L);
        assertThat(stored.get(0).getPath()).isEqualTo("page_files/img.png");
        assertThat(stored.get(0).getContentType()).isEqualTo("image/png");
        assertThat(stored.get(1).getPath()).isEqualTo("page_files/스타일.css");
        assertThat(stored.get(1).getContentType()).isEqualTo("text/css");
    }

    @Test
    void createMultipart_htmlOnly_worksWithoutAssets() throws Exception {
        mockSavedWithId(6L);

        mockMvc.perform(multipart("/api/artifacts")
                        .param("html", "<html><title>제목만</title></html>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목만"))
                .andExpect(jsonPath("$.assetCount").value(0));
        verify(assetRepository).saveAll(List.of());
    }

    @Test
    void createMultipart_traversalPath_rejected() throws Exception {
        mockMvc.perform(multipart("/api/artifacts")
                        .file(new MockMultipartFile("assets", "x", null, new byte[]{1}))
                        .param("assetPaths", "../secret.txt")
                        .param("html", "<html></html>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(repository, never()).save(any());
        verify(assetRepository, never()).saveAll(any());
    }

    @Test
    void createMultipart_duplicateAfterNormalization_rejected() throws Exception {
        // "./page_files/a.png" 는 "page_files/a.png" 로 정규화 → 중복
        mockMvc.perform(multipart("/api/artifacts")
                        .file(new MockMultipartFile("assets", "a", null, new byte[]{1}))
                        .file(new MockMultipartFile("assets", "b", null, new byte[]{2}))
                        .param("assetPaths", "page_files/a.png")
                        .param("assetPaths", "./page_files/a.png")
                        .param("html", "<html></html>"))
                .andExpect(status().isBadRequest());
        verify(assetRepository, never()).saveAll(any());
    }

    @Test
    void createMultipart_countMismatch_rejected() throws Exception {
        mockMvc.perform(multipart("/api/artifacts")
                        .file(new MockMultipartFile("assets", "a", null, new byte[]{1}))
                        .param("html", "<html></html>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(assetRepository, never()).saveAll(any());
    }

    @Test
    void createMultipart_oversizeAsset_rejected() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        mockMvc.perform(multipart("/api/artifacts")
                        .file(new MockMultipartFile("assets", "big.png", null, big))
                        .param("assetPaths", "big.png")
                        .param("html", "<html></html>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(repository, never()).save(any());
    }

    @Test
    void normalizeAssetPath_rules() {
        assertThat(ArtifactController.normalizeAssetPath("page_files/img.png")).isEqualTo("page_files/img.png");
        assertThat(ArtifactController.normalizeAssetPath("./page_files/img.png")).isEqualTo("page_files/img.png");
        assertThat(ArtifactController.normalizeAssetPath("한글폴더/이미지.png")).isEqualTo("한글폴더/이미지.png");
        assertThat(ArtifactController.normalizeAssetPath(null)).isNull();
        assertThat(ArtifactController.normalizeAssetPath("  ")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("/abs/path.png")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("a/../b.png")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("a\\b.png")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("a//b.png")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("a/./b.png")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("dir/")).isNull();
        assertThat(ArtifactController.normalizeAssetPath("x".repeat(501))).isNull();
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
    void view_bareUrl_redirectsToTrailingSlash() throws Exception {
        // 상대 자산 참조가 {id}/ 기준으로 풀리도록 정식 URL 은 trailing slash — 구 공유 링크는 301.
        mockMvc.perform(get("/artifacts/view/7"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/artifacts/view/7/"));
    }

    @Test
    void view_servesHtmlWithCspSandbox() throws Exception {
        // XSS 방어 트립와이어: 뷰어는 반드시 CSP sandbox 로 서빙 — 이 헤더가 빠지면
        // 업로드된 HTML 이 same-origin 으로 대시보드 API 를 호출할 수 있다.
        when(repository.findById(7L)).thenReturn(Optional.of(saved("t", "<html><body>hi</body></html>")));

        mockMvc.perform(get("/artifacts/view/7/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("hi"));
    }

    @Test
    void view_notFound_returns404Html() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/artifacts/view/99/"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("찾을 수 없습니다"));
    }

    @Test
    void view_servesAssetWithStoredTypeAndCsp() throws Exception {
        // 자산도 CSP sandbox 필수 — 직접 열리는 HTML/SVG 자산이 same-origin 이 되면 방어가 뚫린다.
        when(assetRepository.findByArtifactIdAndPath(7L, "page_files/img.png"))
                .thenReturn(Optional.of(new ArtifactAssetEntity(7L, "page_files/img.png",
                        "image/png", new byte[]{9, 8, 7})));

        mockMvc.perform(get("/artifacts/view/7/page_files/img.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .containsExactly(9, 8, 7));
    }

    @Test
    void view_missingAsset_returns404Plain() throws Exception {
        when(assetRepository.findByArtifactIdAndPath(7L, "nope.css")).thenReturn(Optional.empty());

        mockMvc.perform(get("/artifacts/view/7/nope.css"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.TEXT_PLAIN_VALUE));
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
