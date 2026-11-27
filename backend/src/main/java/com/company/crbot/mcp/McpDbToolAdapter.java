package com.company.crbot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpDbToolAdapter implements DbToolPort {

    private final McpToolRegistry registry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public McpDbToolAdapter(McpToolRegistry registry, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> listTables(String schema) {
        // MCP 适配器只按工具协议转发，不直接拼 SQL
        String result = registry.call("db_list_tables", args("schema", schema));
        if (result.isBlank()) {
            return List.of();
        }
        return List.of(result.split("\\n"));
    }

    @Override
    public String describeTable(String table) {
        return registry.call("db_describe_table", args("table", table));
    }

    @Override
    public String listIndexes(String table) {
        return registry.call("db_list_indexes", args("table", table));
    }

    @Override
    public String explainSql(String sql) {
        return registry.call("db_explain_sql", args("sql", sql));
    }

    private JsonNode args(String key, String value) {
        return objectMapper.createObjectNode().put(key, value == null ? "" : value);
    }
}
