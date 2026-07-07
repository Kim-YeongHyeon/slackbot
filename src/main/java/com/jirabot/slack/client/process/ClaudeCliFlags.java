package com.jirabot.slack.client.process;

import java.util.List;

/**
 * 분류/변환용 `claude -p` 호출에 공통 적용하는 경량화 플래그.
 * <p>
 * STUDY: 실측 근거 (v0.0.58) —
 * <ul>
 *   <li>{@code --tools ""} — 도구 스키마를 시스템 프롬프트에서 제거. 분류엔 도구가 무용한데
 *       기본 포함 시 시스템 프롬프트가 ~22k 토큰(도구 스키마+CLAUDE.md+환경 섹션), 제거 시 ~3.2k.</li>
 *   <li>{@code --exclude-dynamic-system-prompt-sections} — git 상태 등 호출마다 바뀌는 섹션 제거.
 *       CLAUDE.md(프로젝트 지침)가 분류 컨텍스트에 주입되는 간섭을 끊고, 프롬프트 캐시 적중을 안정화.</li>
 *   <li>{@code --no-session-persistence} — 매 호출 세션 jsonl 기록 중단
 *       (기존 ~/.claude/projects 에 418개/35MB 누적돼 있었음).</li>
 *   <li>{@code --disable-slash-commands} — 스킬 디렉토리 스캔 생략.</li>
 * </ul>
 * 시스템 프롬프트는 {@code --system-prompt-file} 로 별도 전달되므로 이 플래그들의 영향을 받지 않는다.
 */
public final class ClaudeCliFlags {

    private ClaudeCliFlags() {}

    public static final List<String> LEAN_FLAGS = List.of(
            "--tools", "",
            "--exclude-dynamic-system-prompt-sections",
            "--no-session-persistence",
            "--disable-slash-commands");
}
