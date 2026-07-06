package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.util.IssueCommandParser.SubtaskCommand;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IssueCommandParserTest {

    // ---------- 양성: 명령형 prefix ----------

    @Test
    void prefixForm_withKeyAndContent() {
        Optional<SubtaskCommand> cmd = IssueCommandParser.parseSubtask("하위작업 ES2-123 로그인 리팩토링");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-123");
        assertThat(cmd.get().parentName()).isNull();
        assertThat(cmd.get().content()).isEqualTo("로그인 리팩토링");
    }

    @Test
    void prefixForm_subtaskEnglishKeyword() {
        Optional<SubtaskCommand> cmd = IssueCommandParser.parseSubtask("subtask ES2-999 add retry logic");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-999");
        assertThat(cmd.get().content()).isEqualTo("add retry logic");
    }

    // ---------- 양성: NL 키형 ----------

    @Test
    void nlKeyForm_quotedContent() {
        Optional<SubtaskCommand> cmd =
                IssueCommandParser.parseSubtask("ES2-1234에 하위작업으로 '테스트 작성' 추가해줘");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-1234");
        assertThat(cmd.get().content()).isEqualTo("테스트 작성");
    }

    @Test
    void nlKeyForm_unquotedContent_isNonBlank() {
        Optional<SubtaskCommand> cmd =
                IssueCommandParser.parseSubtask("ES2-1234에 하위작업으로 로그인 리팩토링 추가해줘");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-1234");
        // 정제는 Sonnet 이 하므로 조사가 조금 남아도 됨 — 핵심 내용이 포함되면 통과.
        assertThat(cmd.get().content()).contains("로그인 리팩토링");
    }

    // ---------- 양성: NL 이름형 ----------

    @Test
    void nlNameForm_storyBelow() {
        Optional<SubtaskCommand> cmd =
                IssueCommandParser.parseSubtask("결제 모듈 스토리 아래에 하위작업으로 '환불 처리' 추가");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isNull();
        assertThat(cmd.get().parentName()).isEqualTo("결제 모듈");
        assertThat(cmd.get().content()).isEqualTo("환불 처리");
    }

    // ---------- 음성 ----------

    @Test
    void noSubtaskKeyword_returnsEmpty() {
        // 하위작업 키워드 없음 → 가로채지 않음(버그 등록 경로 보존)
        assertThat(IssueCommandParser.parseSubtask("ES2-123 때문에 빌드가 깨져요")).isEmpty();
    }

    @Test
    void threadStyleKeywordOnly_noKeyNoName_returnsEmpty() {
        // 스레드 안 "하위작업 <내용>" — 키/이름 없음 → empty(기존 스레드 경로로 폴스루)
        assertThat(IssueCommandParser.parseSubtask("하위작업 로그인 처리 개선")).isEmpty();
    }

    @Test
    void twoKeys_ambiguousParent_returnsEmpty() {
        assertThat(IssueCommandParser.parseSubtask("ES2-1에 하위작업 ES2-2 추가")).isEmpty();
    }

    @Test
    void nullOrBlank_returnsEmpty() {
        assertThat(IssueCommandParser.parseSubtask(null)).isEmpty();
        assertThat(IssueCommandParser.parseSubtask("   ")).isEmpty();
    }

    @Test
    void keyPresentButNoContent_returnsCommandWithBlankContent() {
        // "ES2-123 하위작업" — 부모는 명확하나 내용 없음 → content 비어있는 command(호출부가 사용법 안내)
        Optional<SubtaskCommand> cmd = IssueCommandParser.parseSubtask("ES2-123 하위작업");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-123");
        assertThat(cmd.get().content()).isBlank();
    }
}
