package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.jirabot.slack.config.DashboardUserDetailsService;
import com.jirabot.slack.entity.ArtifactAssetEntity;
import com.jirabot.slack.entity.ArtifactEntity;
import com.jirabot.slack.entity.DashboardUserEntity;
import com.jirabot.slack.repository.ArtifactAssetRepository;
import com.jirabot.slack.repository.ArtifactFolderRepository;
import com.jirabot.slack.repository.ArtifactRepository;
import com.jirabot.slack.repository.DashboardUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class ArtifactControllerTest {

    private ArtifactRepository repository;
    private ArtifactAssetRepository assetRepository;
    private ArtifactFolderRepository folderRepository;
    private MockMvc mockMvc;

    // 업로드한 로그인 계정 (v0.0.83 — 올린 사람은 로그인 계정 이름으로 자동 기록)
    private static final UsernamePasswordAuthenticationToken KIM = new UsernamePasswordAuthenticationToken(
            "kim", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    private static final UsernamePasswordAuthenticationToken ADMIN = new UsernamePasswordAuthenticationToken(
            "yhkim", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    @BeforeEach
    void setUp() {
        repository = mock(ArtifactRepository.class);
        assetRepository = mock(ArtifactAssetRepository.class);
        DashboardUserRepository users = mock(DashboardUserRepository.class);
        when(users.findByUsername("kim"))
                .thenReturn(Optional.of(new DashboardUserEntity("kim", "김철수", "{bcrypt}h")));
        folderRepository = mock(ArtifactFolderRepository.class);
        when(folderRepository.existsById(3L)).thenReturn(true);   // 존재하는 폴더 id=3
        mockMvc = standaloneSetup(new ArtifactController(repository, assetRepository,
                new DashboardUserDetailsService(users, "yhkim", "pw", "김영현"), folderRepository)).build();
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
            public long getOpenCommentCount() { return 4; }
            public Long getFolderId() { return 3L; }
        }));

        mockMvc.perform(get("/api/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("주간 리포트"))
                .andExpect(jsonPath("$[0].sizeBytes").value(2048))
                .andExpect(jsonPath("$[0].assetCount").value(3))
                .andExpect(jsonPath("$[0].openCommentCount").value(4))
                .andExpect(jsonPath("$[0].folderId").value(3))
                .andExpect(jsonPath("$[0].html").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("html"));
        // findAll() 을 쓰면 html 이 딸려온다 — projection 전용 메서드만 호출해야 한다.
        verify(repository, never()).findAll();
    }

    @Test
    void embedded_returnsSandboxedSelfContainedHtml() throws Exception {
        when(repository.findById(7L)).thenReturn(Optional.of(
                saved("report", "<html><img src='images/plot.png'></html>")));
        when(assetRepository.findByArtifactId(7L)).thenReturn(List.of(
                new ArtifactAssetEntity(7, "images/plot.png", "image/png", new byte[]{1, 2, 3})));

        mockMvc.perform(get("/api/artifacts/7/embedded"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Sol-Artifact", "embedded"))
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("data:image/png;base64,")
                        .doesNotContain("src=\"images/plot.png\""));
        verify(assetRepository).findByArtifactId(7L);
    }

    @Test
    void embedded_missingArtifact_returns404() throws Exception {
        mockMvc.perform(get("/api/artifacts/99/embedded"))
                .andExpect(status().isNotFound());
        verify(assetRepository, never()).findByArtifactId(99L);
    }

    @Test
    void create_savesAndReturnsIdTitle() throws Exception {
        mockSavedWithId(1L);

        // body 에 다른 사람 이름을 넣어도 무시 — 올린 사람은 로그인 계정(김철수)
        mockMvc.perform(post("/api/artifacts").principal(KIM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"리포트\",\"html\":\"<html></html>\",\"author\":\"김영현\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("리포트"));

        ArgumentCaptor<ArtifactEntity> cap = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getHtml()).isEqualTo("<html></html>");
        assertThat(cap.getValue().getAuthor()).isEqualTo("김철수");
    }

    @Test
    void createMultipart_uploaderFromLogin_ignoresAuthorParam() throws Exception {
        mockSavedWithId(5L);
        mockMvc.perform(multipart("/api/artifacts").principal(ADMIN)
                        .param("html", "<html></html>")
                        .param("author", "다른사람"))
                .andExpect(status().isOk());
        ArgumentCaptor<ArtifactEntity> cap = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getAuthor()).isEqualTo("김영현");   // 관리자 표시 이름
    }

    @Test
    void update_keepsOriginalUploader_evenWhenEditedByOther() throws Exception {
        // 수정은 내용만 바뀐다 — 올린 사람은 최초 업로더 유지
        ArtifactEntity existing = new ArtifactEntity("t", "김영현", "<html>old</html>");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        mockMvc.perform(put("/api/artifacts/7").principal(KIM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<html>new</html>\",\"author\":\"아무개\"}"))
                .andExpect(status().isOk());
        assertThat(existing.getAuthor()).isEqualTo("김영현");
        assertThat(existing.getHtml()).isEqualTo("<html>new</html>");
    }

    @Test
    void update_blankUploader_filledWithEditor() throws Exception {
        // 옛 데이터(올린 사람 미기록)는 수정한 사람으로 채운다
        ArtifactEntity existing = new ArtifactEntity("t", null, "<html>old</html>");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/artifacts/7").principal(KIM)
                        .param("html", "<html>new</html>"))
                .andExpect(status().isOk());
        assertThat(existing.getAuthor()).isEqualTo("김철수");
    }

    // ===== 폴더 (v0.0.86) =====

    @Test
    void moveToFolder_setsFolder_and_nullMeansUnclassified() throws Exception {
        ArtifactEntity a = saved("t", "<html></html>");
        when(repository.findById(7L)).thenReturn(Optional.of(a));

        mockMvc.perform(patch("/api/artifacts/7/folder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(3));
        assertThat(a.getFolderId()).isEqualTo(3L);

        mockMvc.perform(patch("/api/artifacts/7/folder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").doesNotExist());
        assertThat(a.getFolderId()).isNull();
    }

    @Test
    void moveToFolder_missingArtifactOrFolder_404_badInput_400() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(patch("/api/artifacts/99/folder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":3}"))
                .andExpect(status().isNotFound());

        ArtifactEntity a = saved("t", "<html></html>");
        when(repository.findById(7L)).thenReturn(Optional.of(a));
        mockMvc.perform(patch("/api/artifacts/7/folder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":42}"))        // 없는 폴더
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/artifacts/7/folder").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))                         // folderId 누락
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/artifacts/7/folder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\":\"설계\"}"))  // 숫자 아님
                .andExpect(status().isBadRequest());
        assertThat(a.getFolderId()).isNull();
    }

    @Test
    void upload_withFolderId_goesIntoFolder_json_and_multipart() throws Exception {
        mockSavedWithId(1L);
        mockMvc.perform(post("/api/artifacts").principal(KIM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<html></html>\",\"folderId\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/artifacts").principal(KIM)
                        .param("html", "<html></html>").param("folderId", "3"))
                .andExpect(status().isOk());
        ArgumentCaptor<ArtifactEntity> cap = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(e -> assertThat(e.getFolderId()).isEqualTo(3L));
    }

    @Test
    void upload_withoutFolderId_isUnclassified_unknownFolder_rejected() throws Exception {
        mockSavedWithId(1L);
        mockMvc.perform(post("/api/artifacts").principal(KIM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<html></html>\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<ArtifactEntity> cap = ArgumentCaptor.forClass(ArtifactEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getFolderId()).isNull();

        mockMvc.perform(multipart("/api/artifacts").principal(KIM)
                        .param("html", "<html></html>").param("folderId", "42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("존재하지 않는 폴더입니다: 42"));
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
        String def = ArtifactController.DEFAULT_TITLE;
        // 명시 제목 우선
        assertThat(ArtifactController.resolveTitle("명시", "<title>태그</title>", "file", def)).isEqualTo("명시");
        // 없으면 <title> 태그
        assertThat(ArtifactController.resolveTitle(null, "<html><title>주간 리포트</title></html>", "f", def))
                .isEqualTo("주간 리포트");
        assertThat(ArtifactController.resolveTitle("", "<TITLE lang=\"ko\">대문자</TITLE>", null, def))
                .isEqualTo("대문자");
        // 태그도 없으면 파일명 → 최후엔 fallback (생성: 기본 문구 / 수정: 기존 제목)
        assertThat(ArtifactController.resolveTitle(null, "<html></html>", "report", def)).isEqualTo("report");
        assertThat(ArtifactController.resolveTitle(null, "<html></html>", null, def)).isEqualTo(def);
        assertThat(ArtifactController.resolveTitle(null, "<html></html>", null, "기존 제목")).isEqualTo("기존 제목");
    }

    // ===== 제자리 수정 (v0.0.74) =====

    @Test
    void updateJson_replacesContentAndClearsAssets_keepsId() throws Exception {
        ArtifactEntity existing = saved("옛 제목", "<html>old</html>");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(put("/api/artifacts/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<html><title>새 제목</title>new</html>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.title").value("새 제목"))
                .andExpect(jsonPath("$.assetCount").value(0));

        assertThat(existing.getHtml()).contains("new");
        assertThat(existing.getTitle()).isEqualTo("새 제목");
        assertThat(existing.getAuthor()).isEqualTo("김영현"); // 미지정 → 유지
        // PUT = 전체 교체 — 자산 미전송 시 기존 자산 삭제
        verify(assetRepository).deleteByArtifactId(7L);
    }

    @Test
    void updateJson_noTitleAnywhere_keepsExistingTitle() throws Exception {
        ArtifactEntity existing = saved("기존 제목", "<html>old</html>");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(put("/api/artifacts/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<html>no title tag</html>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기존 제목"));
    }

    @Test
    void updateMultipart_replacesAssets_deleteBeforeInsert() throws Exception {
        ArtifactEntity existing = saved("t", "<html>old</html>");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/artifacts/7")
                        .file(new MockMultipartFile("assets", "n.css", null, new byte[]{1}))
                        .param("assetPaths", "page_files/new.css")
                        .param("html", "<html>v2</html>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetCount").value(1));

        // UNIQUE(artifact_id, path) 위반 방지 — 기존 자산 벌크 삭제가 saveAll 보다 먼저여야 한다.
        InOrder inOrder = inOrder(assetRepository);
        inOrder.verify(assetRepository).deleteByArtifactId(7L);
        inOrder.verify(assetRepository).saveAll(any());
        assertThat(existing.getHtml()).isEqualTo("<html>v2</html>");
    }

    @Test
    void update_missingId_returns404() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/artifacts/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<html></html>\"}"))
                .andExpect(status().isNotFound());
        verify(assetRepository, never()).deleteByArtifactId(99L);
    }

    @Test
    void update_blankHtml_rejected_keepsAssets() throws Exception {
        when(repository.findById(7L)).thenReturn(Optional.of(saved("t", "<html>old</html>")));

        mockMvc.perform(put("/api/artifacts/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        verify(assetRepository, never()).deleteByArtifactId(7L);
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
                // v0.0.75: XFO SAMEORIGIN (리뷰 iframe 허용) + 에이전트 <script> 주입은 유지되어야 한다.
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(result -> {
                    String out = result.getResponse().getContentAsString();
                    assertThat(out).contains("hi");
                    // v0.0.88: 외부 src 가 아니라 인라인 — 타사 쿠키 차단 브라우저에서도 동작해야 한다
                    assertThat(out).contains("<script data-sol-comment-agent>");
                    assertThat(out).doesNotContain("src=\"/artifacts/view/7/" + ArtifactController.AGENT_PATH);
                });
        // 주입은 새 문자열 생성일 뿐 — DB(엔티티)를 저장하지 않는다 (원문 불변).
        verify(repository, never()).save(any());
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
                // v0.0.75: 자산 응답에도 XFO SAMEORIGIN
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                // 캐시 금지 (v0.0.74) — 제자리 수정 후 구버전 자산이 보이면 안 된다
                .andExpect(header().doesNotExist("Cache-Control"))
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

    // ===== 인라인 댓글 에이전트 주입 (v0.0.75) =====

    @Test
    void injectAgent_inlinesScriptBeforeLastBodyClose() {
        String out = ArtifactController.injectAgent("<html><body>hi</body></html>", "AGENT();");
        assertThat(out).isEqualTo("<html><body>hi<script data-sol-comment-agent>AGENT();</script></body></html>");
        assertThat(countOccurrences(out, "data-sol-comment-agent")).isEqualTo(1);
    }

    @Test
    void injectAgent_lastBodyClose_whenMultiple() {
        // 본문 중간(예: 코드 예시)의 </body> 가 아니라 마지막 </body> 앞에 넣는다
        String out = ArtifactController.injectAgent("<body>a</body><body>b</body>", "X");
        int scriptIdx = out.indexOf("data-sol-comment-agent");
        assertThat(scriptIdx).isGreaterThan(out.indexOf("b"));
        assertThat(countOccurrences(out, "data-sol-comment-agent")).isEqualTo(1);
    }

    @Test
    void injectAgent_uppercaseBodyTag_matchedCaseInsensitively() {
        String out = ArtifactController.injectAgent("<HTML><BODY>hi</BODY></HTML>", "X");
        assertThat(out).contains("<script data-sol-comment-agent>X</script></BODY>");
    }

    @Test
    void injectAgent_noBodyTag_appendsAtEnd() {
        String out = ArtifactController.injectAgent("<div>no body tag</div>", "X");
        assertThat(out).endsWith("<script data-sol-comment-agent>X</script>");
    }

    @Test
    void injectAgent_escapesClosingScriptInsideAgent() {
        // 에이전트 본문의 </script> 가 인라인 블록을 조기 종료시키면 안 된다 (대소문자 무관)
        String out = ArtifactController.injectAgent("<body></body>", "var s='</SCRIPT>';");
        assertThat(out).contains("var s='<\\/SCRIPT>';");
        assertThat(countOccurrences(out.toLowerCase(), "</script>")).isEqualTo(1);
    }

    @Test
    void reviewPage_redirectsToStaticSpaWithIdParam() throws Exception {
        mockMvc.perform(get("/artifacts/review/5"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/artifacts/review/index.html?id=5"));
    }

    @Test
    void reviewPage_staticFilenamesDoNotMatchIdMapping() throws Exception {
        // {id:\d+} 회귀 방지 — 정규식이 빠지면 index.html 이 {id} 에 매칭되어 long 변환 400 으로
        // 리뷰 페이지(js/css 포함)가 전부 깨진다. standalone 에선 정적 핸들러가 없어 404 가 정상.
        mockMvc.perform(get("/artifacts/review/index.html"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/artifacts/review/review.js"))
                .andExpect(status().isNotFound());
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
