package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpToolRegistry {

    private final JdbcDbToolAdapter jdbcDbToolAdapter;

    public McpToolRegistry(JdbcDbToolAdapter jdbcDbToolAdapter) {
        this.jdbcDbToolAdapter = jdbcDbToolAdapter;
    }

    public String call(String name, JsonNode arguments) {
        JsonNode args = arguments == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : arguments;
        // 工具名与规格 5.3 对齐，具体执行仍走只读 JDBC
        return switch (name) {
            case "db_list_tables" -> String.join("\n", jdbcDbToolAdapter.listTables(text(args, "schema")));
            case "db_describe_table" -> jdbcDbToolAdapter.describeTable(required(args, "table"));
            case "db_list_indexes" -> jdbcDbToolAdapter.listIndexes(required(args, "table"));
            case "db_explain_sql" -> jdbcDbToolAdapter.explainSql(required(args, "sql"));
            default -> throw new ApiException("未知的 MCP 工具: " + name);
        };
    }

    public List<ToolSpec> tools() {
        return List.of(
                new ToolSpec("db_list_tables", "列出只读库中的表", "{\"type\":\"object\",\"properties\":{\"schema\":{\"type\":\"string\"}}}"),
                new ToolSpec("db_describe_table", "查看表字段、类型、可空和注释", "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}"),
                new ToolSpec("db_list_indexes", "查看表索引", "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}"),
                new ToolSpec("db_explain_sql", "只执行 EXPLAIN", "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}")
        );
    }

    private String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private String required(JsonNode args, String field) {
        String value = text(args, field);
        if (value == null || value.isBlank()) {
            throw new ApiException("缺少参数 " + field);
        }
        return value;
    }

    public record ToolSpec(String name, String description, String inputSchema) {
    }
}
