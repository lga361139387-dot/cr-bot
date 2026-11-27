package com.company.crbot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 给 Cursor 等 MCP 客户端的入口。与评审 Agent 用的 /api/v1/mcp 库表工具分开。
 */
@RestController
@RequestMapping("/api/v1/ide-mcp")
public class IdeMcpController {

    private final IdeMcpHandler handler;

    public IdeMcpController(IdeMcpService ideMcpService, ObjectMapper objectMapper) {
        this.handler = new IdeMcpHandler(ideMcpService, objectMapper);
    }

    /**
     * purpose：应答一次 MCP JSON-RPC。
     * business-logic：无 id 的通知返回 202 且无正文。
     * params：request 为客户端 POST 的 JSON-RPC。
     * return：有结果时 200；通知为 202。
     * exception：协议错误写在响应体里，不另抛 HTTP 异常。
     */
    @PostMapping
    public ResponseEntity<ObjectNode> handle(@RequestBody JsonNode request) {
        ObjectNode body = handler.handle(request);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }
        return ResponseEntity.ok(body);
    }

    /**
     * purpose：表明本端点不提供 SSE 长连接。
     * business-logic：Streamable HTTP 允许只用 POST；GET 按规范返回 405。
     * params：无。
     * return：405。
     * exception：无。
     */
    @GetMapping
    public ResponseEntity<Void> sseUnsupported() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
