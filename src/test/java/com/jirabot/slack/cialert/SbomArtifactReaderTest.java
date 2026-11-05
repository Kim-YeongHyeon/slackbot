package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubActionsClient.ArtifactInfo;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SbomArtifactReaderTest {

    private GitHubActionsClient github;
    private SbomArtifactReader reader;

    @BeforeEach
    void setUp() {
        github = mock(GitHubActionsClient.class);
        reader = new SbomArtifactReader(github, new ObjectMapper());
    }

    private static byte[] zipWith(String name, byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void readsOnlyLiveGrypeArtifacts() throws Exception {
        when(github.listArtifacts("envector", 9)).thenReturn(List.of(
                new ArtifactInfo(1, "grype-linux", 100, false),
                new ArtifactInfo(2, "grype-macos", 100, true),     // 만료
                new ArtifactInfo(3, "wheels-linux", 100, false)));  // 다른 아티팩트
        when(github.downloadArtifactZip("envector", 1, SbomArtifactReader.MAX_ZIP_BYTES)).thenReturn(zipWith(
                "envector-backend-main-amd64-grype.json",
                GrypeReportTest.grype(GrypeReportTest.match("CVE-1", "High", "p", "1", "deb", "fixed", "\"2\"", "", ""))));

        assertThat(reader.read("envector", 9)).get().satisfies(r -> assertThat(r.findings()).hasSize(1));
        verify(github, never()).downloadArtifactZip(eq("envector"), eq(2L), anyInt());
        verify(github, never()).downloadArtifactZip(eq("envector"), eq(3L), anyInt());
    }

    @Test
    void heKmsSbomArtifact_isRead() throws Exception {
        // HE-KMS release.yaml 은 grype JSON 을 SBOM 과 함께 "sbom" 아티팩트로 올린다
        when(github.listArtifacts("HE-KMS", 5)).thenReturn(List.of(new ArtifactInfo(8, "sbom", 100, false)));
        when(github.downloadArtifactZip("HE-KMS", 8, SbomArtifactReader.MAX_ZIP_BYTES)).thenReturn(zipWith(
                "he-kms-crypto-main-amd64-grype.json",
                GrypeReportTest.grype(GrypeReportTest.match("GO-1", "Critical", "stdlib", "go1.26.2", "go-module", "fixed", "\"1.26.9\"", "", ""))));

        assertThat(reader.read("HE-KMS", 5)).get().satisfies(r ->
                assertThat(r.findings()).singleElement().satisfies(f -> assertThat(f.images()).containsExactly("he-kms-crypto")));
    }

    @Test
    void noGrypeArtifact_isEmpty() {
        when(github.listArtifacts("envector", 9)).thenReturn(List.of(new ArtifactInfo(3, "wheels", 1, false)));

        assertThat(reader.read("envector", 9)).isEmpty();
        verify(github, never()).downloadArtifactZip(anyString(), anyLong(), anyInt());
    }

    @Test
    void downloadFailureOrNoGrypeFilesInZip_isEmpty() throws Exception {
        when(github.listArtifacts("envector", 9)).thenReturn(List.of(new ArtifactInfo(1, "grype-linux", 1, false)));
        when(github.downloadArtifactZip("envector", 1, SbomArtifactReader.MAX_ZIP_BYTES))
                .thenThrow(new GitHubActionsException(413, "too big"));
        assertThat(reader.read("envector", 9)).isEmpty();

        // doReturn — 앞에서 예외로 스텁한 메서드를 when(...) 안에서 다시 호출하지 않게.
        org.mockito.Mockito.doReturn(zipWith("readme.txt", "x".getBytes()))
                .when(github).downloadArtifactZip("envector", 1, SbomArtifactReader.MAX_ZIP_BYTES);
        assertThat(reader.read("envector", 9)).isEmpty();
    }
}
