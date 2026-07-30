package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.util.IssueCommandParser.SubtaskCommand;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// STUDY: v0.0.65부터 NL 조작 파싱은 skill-issue-action(Sonnet) 담당 — 여기는 0초-지연 명령형 prefix 만 검증.
//        NL 문장의 추출 정확도는 SkillActionEvalTest(골든셋) 가 담당한다.
class IssueCommandParserTest {

    @Test
    void prefixForm_withKeyAndContent() {
        Optional<SubtaskCommand> cmd = IssueCommandParser.parseSubtaskPrefix("하위작업 ES2-123 로그인 리팩토링");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-123");
        assertThat(cmd.get().parentName()).isNull();
        assertThat(cmd.get().content()).isEqualTo("로그인 리팩토링");
    }

    @Test
    void prefixForm_englishKeyword() {
        Optional<SubtaskCommand> cmd = IssueCommandParser.parseSubtaskPrefix("subtask ES2-999 add retry logic");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().parentKey()).isEqualTo("ES2-999");
        assertThat(cmd.get().content()).isEqualTo("add retry logic");
    }

    @Test
    void prefixForm_quotedContent() {
        Optional<SubtaskCommand> cmd = IssueCommandParser.parseSubtaskPrefix("하위작업 ES2-1 '테스트 작성'");
        assertThat(cmd).isPresent();
        assertThat(cmd.get().content()).isEqualTo("테스트 작성");
    }

    @Test
    void nonPrefixForms_notClaimed() {
        // NL 형은 스킬 담당 — prefix 파서는 가로채지 않는다.
        assertThat(IssueCommandParser.parseSubtaskPrefix("ES2-1234에 하위작업으로 '테스트' 추가해줘")).isEmpty();
        assertThat(IssueCommandParser.parseSubtaskPrefix("하위작업 로그인 처리 개선")).isEmpty(); // 키 없음(스레드형)
        assertThat(IssueCommandParser.parseSubtaskPrefix(null)).isEmpty();
        assertThat(IssueCommandParser.parseSubtaskPrefix("   ")).isEmpty();
    }

    @Test
    void validStoryPoints_isTeamScale() {
        assertThat(IssueCommandParser.VALID_STORY_POINTS).containsExactlyInAnyOrder(1, 2, 3, 5, 8);
    }
}
