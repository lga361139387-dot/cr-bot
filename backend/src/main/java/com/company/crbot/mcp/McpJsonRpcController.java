package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp")
public class McpJsonRpcController {

    private final McpToolRegistry registry;
    private final ObjectMapper objectMapper;

    public McpJsonRpcController(McpToolRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ObjectNode handle(@RequestBody JsonNode request) {
        Object id = request.get("id") == null || request.get("id").isNull() ? null : request.get("id");
        String method = request.path("method").asText("");
        try {
            // 只实现只读 DB 工具的 MCP JSON-RPC
            if ("initialize".equals(method)) {
                ObjectNode result = objectMapper.createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "crbot-mcp-db").put("version", "1.0.0");
                return ok(id, result);
            }
            if ("tools/list".equals(method)) {
                ObjectNode result = objectMapper.createObjectNode();
                com.fasterxml.jackson.databind.node.ArrayNode tools = result.putArray("tools");
                for (McpToolRegistry.ToolSpec spec : registry.tools()) {
                    ObjectNode tool = tools.addObject();
                    tool.put("name", spec.name());
                    tool.put("description", spec.description());
                    tool.set("inputSchema", objectMapper.readTree(spec.inputSchema()));
                }
                return ok(id, result);
            }
            if ("tools/call".equals(method)) {
                JsonNode params = request.path("params");
                String name = params.path("name").asText();
                String content = registry.call(name, params.get("arguments"));
                ObjectNode result = objectMapper.createObjectNode();
                result.putArray("content").addObject().put("type", "text").put("text", content);
                return ok(id, result);
            }
            return error(id, -32601, "不支持的方法");
        } catch (ApiException ex) {
            return error(id, -32000, ex.getMessage());
        } catch (Exception ex) {
            return error(id, -32603, "MCP 调用失败");
        }
    }

    private ObjectNode ok(Object id, JsonNode result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id instanceof JsonNode jsonNode) {
            node.set("id", jsonNode);
        }
        node.set("result", result);
        return node;
    }

    private ObjectNode error(Object id, int code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id instanceof JsonNode jsonNode) {
            node.set("id", jsonNode);
        }
        node.putObject("error").put("code", code).put("message", message);
        return node;
    }
}
