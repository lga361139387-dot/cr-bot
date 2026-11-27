package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdeMcpHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initializeListsToolsAndRejectsUnknownTool() throws Exception {
        IdeMcpHandler handler = new IdeMcpHandler(new FakePort(), objectMapper);
        ObjectNode init = handler.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}
                """));
        assertEquals("2025-03-26", init.path("result").path("protocolVersion").asText());
        assertEquals("crbot", init.path("result").path("serverInfo").path("name").asText());

        ObjectNode listed = handler.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """));
        assertEquals(3, listed.path("result").path("tools").size());

        ObjectNode called = handler.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_knowledge","arguments":{"query":"退款"}}}
                """));
        assertEquals("退款", called.path("result").path("content").get(0).path("text").asText());

        ObjectNode missing = handler.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"drop_table"}}
                """));
        assertEquals(-32000, missing.path("error").path("code").asInt());
        assertNull(handler.handle(objectMapper.readTree("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """)));
    }

    private static final class FakePort implements IdeMcpPort {
        @Override
        public String listReviews(JsonNode args) {
            return "[]";
        }

        @Override
        public String getReview(JsonNode args) {
            return "{}";
        }

        @Override
        public String searchKnowledge(JsonNode args) {
            // 空问题直接拒绝，用来确认工具错误会回到 JSON-RPC
            if (args.path("query").asText().isBlank()) {
                throw new ApiException("缺少参数 query");
            }
            return args.path("query").asText();
        }
    }
}
