package com.jirabot.slack.cialert;

import java.util.List;

// STUDY: GitHub Actions 조회 전용 클라이언트 (GITHUB_ALERT_TOKEN). 기존 GitHubApiClient(브랜치 생성,
//        GITHUB_BRANCH_TOKEN)와 토큰·권한 범위가 달라 분리했다. 실패는 GitHubActionsException 으로 던진다.
public interface GitHubActionsClient {

    RunInfo getRun(String repo, long runId);

    // 해당 attempt 의 job 전부 (100개 단위 페이지를 모두 읽는다).
    List<JobInfo> listJobs(String repo, long runId, int attempt);

    // job 로그의 마지막 maxBytes 바이트. 로그 API 는 서명된 저장소 URL 로 302 리다이렉트한다.
    String downloadJobLogTail(String repo, long jobId, int maxBytes);

    CommitInfo getCommit(String repo, String sha);

    // 같은 워크플로의 최근 run (최신순). 이번 run 도 포함될 수 있다.
    List<RunSummary> recentRuns(String repo, long workflowId, String branch, int count);

    List<ArtifactInfo> listArtifacts(String repo, long runId);

    // 아티팩트 zip 전체. maxBytes 를 넘으면 GitHubActionsException (부분 zip 은 풀 수 없으므로 자르지 않는다).
    byte[] downloadArtifactZip(String repo, long artifactId, int maxBytes);

    // path: 워크플로 파일 경로 (".github/workflows/ci.yaml") — 연속 실패 자동 수정(v0.0.100)이 에이전트에게 알려 준다.
    record RunInfo(long id, String name, long workflowId, String headSha, String headBranch,
                   String event, String conclusion, int runAttempt, String htmlUrl,
                   String commitMessage, String commitAuthor, String path) {
        public RunInfo(long id, String name, long workflowId, String headSha, String headBranch,
                       String event, String conclusion, int runAttempt, String htmlUrl,
                       String commitMessage, String commitAuthor) {
            this(id, name, workflowId, headSha, headBranch, event, conclusion, runAttempt, htmlUrl,
                    commitMessage, commitAuthor, "");
        }
    }

    record JobInfo(long id, String name, String conclusion, List<StepInfo> steps) {
        public boolean failed() {
            return "failure".equals(conclusion) || "timed_out".equals(conclusion);
        }

        public List<String> failedStepNames() {
            return steps.stream().filter(s -> "failure".equals(s.conclusion())).map(StepInfo::name).toList();
        }
    }

    record StepInfo(String name, String conclusion) {}

    record CommitInfo(String sha, String message, String author, List<String> files) {}

    record RunSummary(long id, String conclusion, String headSha) {}

    record ArtifactInfo(long id, String name, long sizeInBytes, boolean expired) {}
}
