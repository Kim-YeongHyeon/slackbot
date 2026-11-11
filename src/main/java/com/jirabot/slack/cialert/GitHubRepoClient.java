package com.jirabot.slack.cialert;

import java.util.List;
import java.util.Optional;

// STUDY: SBOM 자동 수정 PR (v0.0.94) 이 쓰는 GitHub 저장소 API — PR 조회·생성, 서브모듈 고정 커밋 조회,
//        커밋 포함 여부(compare). 토큰은 GITHUB_ALERT_TOKEN (4개 레포 Contents·PR 쓰기).
//        서브모듈은 봇이 올리지 않는다(v0.0.97) — 팀의 서브모듈 업데이트 PR 을 찾아 알리기만 한다.
public interface GitHubRepoClient {

    // state: open | closed | all — 최신 100개
    List<PullInfo> listPulls(String repo, String state);

    PullInfo createPull(String repo, String head, String base, String title, String body);

    // PR 이 바꾼 파일 경로 (최대 50개) — 연속 실패 자동 수정(v0.0.100)이 "이미 고치는 PR 이 있나"를 볼 때 쓴다.
    List<String> pullFiles(String repo, int number);

    String defaultBranch(String repo);

    // 브랜치 HEAD 커밋 sha. 없으면 empty.
    Optional<String> branchHead(String repo, String branch);

    // 경로가 서브모듈(gitlink)이면 고정된 커밋 sha.
    Optional<String> submodulePin(String repo, String path, String ref);

    // ancestorSha 가 sha 의 이력에 들어 있는가 (같은 커밋 포함).
    boolean contains(String repo, String ancestorSha, String sha);

    // mergeCommitSha: 머지된 PR 이 base 에 남긴 커밋 (머지 전이면 의미 없음)
    record PullInfo(int number, String title, String body, String htmlUrl, String headRef, boolean merged,
                    String mergeCommitSha) {
        public String text() {
            return (title == null ? "" : title) + "\n" + (body == null ? "" : body);
        }
    }
}
