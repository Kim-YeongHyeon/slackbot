package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockKitBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildBranchRepoButtons_hasButtonPerRepoWithEncodedValue() throws Exception {
        String json = BlockKitBuilder.buildBranchRepoButtons(
                "ES2-1", "feature/ES2-1-foo", List.of("envector-msa", "evi"));
        JsonNode blocks = MAPPER.readTree(json);

        // 권장 브랜치명이 안내 section 에 포함.
        assertThat(json).contains("feature/ES2-1-foo");

        // 마지막 블록이 actions 이고 repo 수만큼 버튼.
        JsonNode actions = blocks.get(blocks.size() - 1);
        assertThat(actions.path("type").asText()).isEqualTo("actions");
        JsonNode elements = actions.path("elements");
        assertThat(elements).hasSize(2);
        // Slack 은 메시지 내 action_id 유일성을 요구 → repo 마다 고유(prefix + 인덱스), 라우팅은 prefix 로.
        assertThat(elements.get(0).path("action_id").asText())
                .startsWith(BlockKitBuilder.ACTION_CREATE_BRANCH).isEqualTo(BlockKitBuilder.ACTION_CREATE_BRANCH + "_0");
        assertThat(elements.get(1).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_CREATE_BRANCH + "_1");
        assertThat(elements.get(0).path("action_id").asText())
                .isNotEqualTo(elements.get(1).path("action_id").asText());
        assertThat(elements.get(0).path("value").asText()).isEqualTo("ES2-1|envector-msa|feature/ES2-1-foo");
        assertThat(elements.get(1).path("value").asText()).isEqualTo("ES2-1|evi|feature/ES2-1-foo");
    }

    // --- 이슈 키 조회 카드 ---

    @Test
    void buildIssueCardBlocks_todoIssue_hasFieldsAndNextStepButtons() throws Exception {
        String json = BlockKitBuilder.buildIssueCardBlocks(
                "ES2-7", "https://j/browse/ES2-7", "로그인 500 에러",
                "버그", "해야 할 일", "해야 할 일", "Alice", "Bob", 3.0, "스프린트 3", "상세 설명입니다");
        JsonNode blocks = MAPPER.readTree(json);

        assertThat(json).contains("ES2-7").contains("로그인 500 에러").contains("Alice").contains("Bob")
                .contains("스프린트 3").contains("상세 설명입니다");

        // fields 2열 섹션 존재
        JsonNode fieldsSection = blocks.get(1);
        assertThat(fieldsSection.path("fields")).hasSize(6);

        // 해야 할 일 → 진행 중 + 바로 완료 버튼, action_id 는 기존 전환 핸들러 재사용 + 유일.
        JsonNode actions = blocks.get(blocks.size() - 1);
        assertThat(actions.path("type").asText()).isEqualTo("actions");
        JsonNode elements = actions.path("elements");
        assertThat(elements).hasSize(2);
        assertThat(elements.get(0).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_IN_PROGRESS);
        assertThat(elements.get(1).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_QUICK_DONE);
        assertThat(elements.get(0).path("value").asText()).isEqualTo("ES2-7");
    }

    @Test
    void buildIssueCardBlocks_inProgressIssue_hasReviewAndDoneButtons() throws Exception {
        String json = BlockKitBuilder.buildIssueCardBlocks(
                "ES2-8", "https://j/browse/ES2-8", "요약", "작업", "진행 중", "진행 중",
                null, null, null, null, null);
        JsonNode blocks = MAPPER.readTree(json);

        JsonNode actions = blocks.get(blocks.size() - 1);
        assertThat(actions.path("type").asText()).isEqualTo("actions");
        JsonNode elements = actions.path("elements");
        assertThat(elements).hasSize(2);
        assertThat(elements.get(0).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_IN_REVIEW);
        assertThat(elements.get(1).path("action_id").asText()).isEqualTo(BlockKitBuilder.ACTION_DONE);
        // 미배정/SP 미설정은 "-" 로 표기
        assertThat(json).contains("-");
    }

    @Test
    void buildIssueCardBlocks_doneIssue_hasNoButtonsAndTruncatesDescription() throws Exception {
        String longDesc = "가".repeat(300);
        String json = BlockKitBuilder.buildIssueCardBlocks(
                "ES2-9", "https://j/browse/ES2-9", "완료된 이슈", "버그", "완료", "완료",
                "Alice", "Bob", 2.0, null, longDesc);
        JsonNode blocks = MAPPER.readTree(json);

        // 완료 이슈는 actions 블록 없음
        for (JsonNode block : blocks) {
            assertThat(block.path("type").asText()).isNotEqualTo("actions");
        }
        // 설명 200자 + 말줄임
        assertThat(json).contains("가".repeat(200) + "…");
        assertThat(json).doesNotContain("가".repeat(201));
    }
}
