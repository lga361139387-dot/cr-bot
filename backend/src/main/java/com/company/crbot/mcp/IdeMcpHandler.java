package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 无状态 MCP JSON-RPC。一次 POST 一个请求，不维护会话。
 */
public class IdeMcpHandler {

    static final List<String> PROTOCOL_VERSIONS = List.of("2024-11-05", "2025-03-26", "2025-06-18");

    private final IdeMcpPort port;
    private final ObjectMapper objectMapper;

    public IdeMcpHandler(IdeMcpPort port, ObjectMapper objectMapper) {
        this.port = port;
        this.objectMapper = objectMapper;
    }

    /**
     * purpose：处理一次 MCP 请求。
     * business-logic：通知没有 id，不回包；工具失败放进 result.isError，方便客户端展示。
     * params：request 为 JSON-RPC 请求体。
     * return：需要回包时返回 JSON；通知返回 null。
     * exception：不向外抛，协议错误写成 JSON-RPC error。
     */
    public ObjectNode handle(JsonNode request) {
        JsonNode idNode = request.get("id");
        boolean hasId = idNode != null && !idNode.isNull();
        String method = request.path("method").asText("");
        try {
            // 通知不回 result，避免客户端把空包当成应答错位
            if (!hasId && method.startsWith("notifications/")) {
                return null;
            }
            if ("initialize".equals(method)) {
                return ok(idNode, initialize(request.path("params")));
            }
            if ("ping".equals(method)) {
                return ok(idNode, objectMapper.createObjectNode());
            }
            if ("tools/list".equals(method)) {
                return ok(idNode, tools());
            }
            if ("resources/list".equals(method) || "resources/templates/list".equals(method)) {
                ObjectNode result = objectMapper.createObjectNode();
                // 本服务只提供工具，资源列表固定为空
                result.putArray(method.endsWith("templates/list") ? "resourceTemplates" : "resources");
                return ok(idNode, result);
            }
            if ("prompts/list".equals(method)) {
                ObjectNode result = objectMapper.createObjectNode();
                result.putArray("prompts");
                return ok(idNode, result);
            }
            if ("tools/call".equals(method)) {
                return ok(idNode, call(request.path("params")));
            }
            return error(idNode, -32601, "不支持的方法");
        } catch (ApiException ex) {
            // 工具参数或业务拒绝仍走 JSON-RPC error，调用方能看到原因
            return error(idNode, -32000, ex.getMessage());
        } catch (RuntimeException ex) {
            return error(idNode, -32603, "MCP 调用失败");
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String version = PROTOCOL_VERSIONS.contains(requested) ? requested : "2025-03-26";
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode capabilities = result.putObject("capabilities");
        // 工具列表不会在运行中变更
        capabilities.putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", "crbot").put("version", "1.0.0");
        return result;
    }

    private ObjectNode tools() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        addTool(tools, "list_reviews", "按项目路径、分支、提交查找 CRBOT 评审。用于对上刚 push 触发的那次评审。",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"projectPath\":{\"type\":\"string\",\"description\":\"GitLab 项目路径或仓库名\"},"
                        + "\"branch\":{\"type\":\"string\"},"
                        + "\"commitSha\":{\"type\":\"string\",\"description\":\"完整或至少 7 位提交 SHA\"},"
                        + "\"pusher\":{\"type\":\"string\"},"
                        + "\"status\":{\"type\":\"string\",\"enum\":[\"PENDING\",\"RUNNING\",\"SUCCESS\",\"FAILED\"]},"
                        + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}"
                        + "}}");
        addTool(tools, "get_review", "读取一次评审的摘要、缺陷、行号、修改建议和人工标注。",
                "{\"type\":\"object\",\"properties\":{\"jobId\":{\"type\":\"integer\"}},\"required\":[\"jobId\"]}");
        addTool(tools, "search_knowledge", "在已索引的 CRBOT 知识库中检索业务说明、会议纪要、方案和用例。",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"query\":{\"type\":\"string\"},"
                        + "\"docType\":{\"type\":\"string\",\"enum\":[\"prototype\",\"logic\",\"meeting\",\"outline\",\"solution\",\"testcase\",\"other\"]},"
                        + "\"topK\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}"
                        + "},\"required\":[\"query\"]}");
        return result;
    }

    private void addTool(ArrayNode tools, String name, String description, String schema) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);
        try {
            tool.set("inputSchema", objectMapper.readTree(schema));
        } catch (Exception ex) {
            throw new IllegalStateException("工具 schema 无法解析: " + name, ex);
        }
    }

    private ObjectNode call(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = objectMapper.createObjectNode();
        }
        String text = switch (name) {
            case "list_reviews" -> port.listReviews(arguments);
            case "get_review" -> port.getReview(arguments);
            case "search_knowledge" -> port.searchKnowledge(arguments);
            default -> throw new ApiException("未知的 MCP 工具: " + name);
        };
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", false);
        return result;
    }

    private ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            node.set("id", id);
        }
        node.set("result", result);
        return node;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            node.set("id", id);
        }
        node.putObject("error").put("code", code).put("message", message);
        return node;
    }
}
