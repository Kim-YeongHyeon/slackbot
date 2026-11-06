package com.jirabot.slack.client.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ClaudeCliFlagsTest {

    @Test
    void leanFlags_removeBuiltInToolsAndEveryMcpServer() throws Exception {
        assertThat(ClaudeCliFlags.LEAN_FLAGS)
                .containsSequence("--tools", "")
                .containsSequence("--strict-mcp-config", "--mcp-config", ClaudeCliFlags.NO_MCP_SERVERS);
        assertThat(new ObjectMapper().readTree(ClaudeCliFlags.NO_MCP_SERVERS).path("mcpServers").isEmpty()).isTrue();
    }

    @Test
    void leanFlags_doNotLetAnyMcpConfigThrough() {
        assertThat(ClaudeCliFlags.LEAN_FLAGS).doesNotContainNull().noneMatch(f -> f.contains(".mcp.json"));
        assertThat(ClaudeCliFlags.LEAN_FLAGS.indexOf("--mcp-config"))
                .isEqualTo(ClaudeCliFlags.LEAN_FLAGS.indexOf("--strict-mcp-config") + 1);
    }
}
