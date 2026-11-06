package com.jirabot.slack.cialert;

import java.util.List;
import java.util.Optional;

// STUDY: SBOM 자동 수정 PR (v0.0.94) 이 쓰는 GitHub 저장소 API — PR 조회·생성, 서브모듈 고정 커밋 조회,
//        clone 없이 gitlink 만 바꾸는 커밋(Git Data API). 토큰은 GITHUB_ALERT_TOKEN (4개 레포 Contents·PR 쓰기).
public interface GitHubRepoClient {

    // state: open | closed | all — 최신 100개
    List<PullInfo> listPulls(String repo, String state);

    PullInfo createPull(String repo, String head, String base, String title, String body);

    String defaultBranch(String repo);

    // 브랜치 HEAD 커밋 sha. 없으면 empty.
    Optional<String> branchHead(String repo, String branch);

    // 경로가 서브모듈(gitlink)이면 고정된 커밋 sha.
    Optional<String> submodulePin(String repo, String path, String ref);

    // base 브랜치 HEAD 에서 path 의 gitlink 만 newSha 로 바꾼 커밋을 만들고 newBranch 를 그 커밋으로 만든다.
    String commitGitlink(String repo, String base, String path, String newSha, String message, String newBranch);

    record PullInfo(int number, String title, String body, String htmlUrl, String headRef, boolean merged) {
        public String text() {
            return (title == null ? "" : title) + "\n" + (body == null ? "" : body);
        }
    }
}
