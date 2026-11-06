package com.jirabot.slack.client.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// STUDY: 외부 프로세스 호출을 얇은 interface 뒤에 두면 테스트에서 Mockito 로 stub 가능.
// STUDY: 직접 ProcessBuilder 호출 시 OS 바이너리 의존 → CI flaky. (lessons.md L3)
public interface ProcessRunner {

    Result run(List<String> command, String stdin, Duration timeout);

    /**
     * 작업 디렉터리와 추가 환경변수를 지정해 실행한다 (v0.0.94 — git·docker 호출용).
     * 비밀값(토큰)은 명령 인자 대신 env 로만 넘겨 프로세스 목록에 남지 않게 한다.
     *
     * @param workDir null 이면 JVM 의 작업 디렉터리
     * @param env     추가·덮어쓸 환경변수 (null/빈 맵 가능)
     */
    Result run(List<String> command, String stdin, Duration timeout, Path workDir, Map<String, String> env);

    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean success() {
            return !timedOut && exitCode == 0;
        }
    }
}
