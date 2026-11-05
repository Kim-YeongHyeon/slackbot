package com.jirabot.slack.cialert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jirabot.slack.cialert.GitHubActionsClient.ArtifactInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// STUDY: run 의 grype 결과 아티팩트(grype-*, sbom)를 받아 GrypeReport 로 만든다 (v0.0.92).
//        아티팩트가 없으면(워크플로 변경 전 run, 만료, 이미지 빌드 실패) empty — 호출부가 로그 분석으로 넘어간다.
@Component
public class SbomArtifactReader {

    private static final Logger log = LoggerFactory.getLogger(SbomArtifactReader.class);
    // envector release.yaml 은 grype-linux / grype-macos, HE-KMS release.yaml 은 sbom 으로 올린다.
    // zip 안에서는 *-grype.json 만 읽으므로 SBOM 파일이 섞여 있어도 된다.
    static final List<String> ARTIFACT_PREFIXES = List.of("grype", "sbom");
    static final int MAX_ZIP_BYTES = 100 * 1024 * 1024;

    private final GitHubActionsClient github;
    private final ObjectMapper objectMapper;

    public SbomArtifactReader(GitHubActionsClient github, ObjectMapper objectMapper) {
        this.github = github;
        this.objectMapper = objectMapper;
    }

    public Optional<GrypeReport> read(String repo, long runId) {
        List<ArtifactInfo> artifacts = github.listArtifacts(repo, runId).stream()
                .filter(a -> ARTIFACT_PREFIXES.stream().anyMatch(a.name()::startsWith) && !a.expired())
                .toList();
        if (artifacts.isEmpty()) {
            return Optional.empty();
        }
        List<byte[]> zips = new ArrayList<>();
        for (ArtifactInfo a : artifacts) {
            try {
                zips.add(github.downloadArtifactZip(repo, a.id(), MAX_ZIP_BYTES));
            } catch (GitHubActionsException e) {
                log.warn("grype artifact download failed repo={} artifact={}: {}", repo, a.name(), e.getMessage());
            }
        }
        if (zips.isEmpty()) {
            return Optional.empty();
        }
        try {
            GrypeReport report = GrypeReport.fromZips(zips, objectMapper);
            return report.scannedFiles() == 0 ? Optional.empty() : Optional.of(report);
        } catch (IOException e) {
            log.warn("grype artifact parse failed repo={} run={}: {}", repo, runId, e.toString());
            return Optional.empty();
        }
    }
}
