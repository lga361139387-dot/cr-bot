package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SqlFirewall {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> BANNED = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "GRANT", "REVOKE", "COPY", "CALL", "CREATE");

    private SqlFirewall() {
    }

    public static String requireTable(String table) {
        if (table == null || table.isBlank()) {
            throw new ApiException("表名不能为空");
        }
        String[] parts = table.trim().split("\\.");
        if (parts.length > 2) {
            throw new ApiException("表名只允许 schema.table");
        }
        for (String part : parts) {
            if (!IDENTIFIER.matcher(part).matches()) {
                throw new ApiException("表名包含非法字符");
            }
        }
        return table.trim();
    }

    public static String requireExplain(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new ApiException("SQL 不能为空");
        }
        String stripped = stripComments(sql).trim();
        int semicolon = stripped.indexOf(';');
        // 只允许结尾一个分号，禁止多语句
        if (semicolon >= 0) {
            if (semicolon != stripped.length() - 1) {
                throw new ApiException("禁止多语句");
            }
            stripped = stripped.substring(0, semicolon).trim();
        }
        String upper = stripped.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("EXPLAIN")) {
            throw new ApiException("仅允许 EXPLAIN");
        }
        if (upper.contains("ANALYZE")) {
            throw new ApiException("禁止 EXPLAIN ANALYZE");
        }
        for (String banned : BANNED) {
            if (containsKeyword(upper, banned)) {
                throw new ApiException("只读防火墙拒绝: " + banned);
            }
        }
        return stripped;
    }

    private static boolean containsKeyword(String upperSql, String keyword) {
        return Pattern.compile("\\b" + keyword + "\\b").matcher(upperSql).find();
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("/\\*.*?\\*/", " ").replaceAll("--.*?(\\r?\\n|$)", " ");
    }
}
