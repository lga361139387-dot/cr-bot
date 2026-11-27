package com.company.crbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FindingCollector {

    private static final Set<String> CATEGORIES = Set.of("standard", "quality", "performance", "security", "business", "database", "function");
    private static final Set<String> SEVERITIES = Set.of("info", "low", "medium", "high");

    private final List<ObjectNode> findings = new ArrayList<>();
    private boolean done;

    public String submit(JsonNode args, Set<String> paths) {
        JsonNode items = args.path("findings");
        if (!items.isArray() || items.isEmpty()) {
            return "错误：findings 必须是非空数组";
        }
        int accepted = 0;
        StringBuilder errors = new StringBuilder();
        for (JsonNode item : items) {
            String path = item.path("filePath").asText("");
            String category = item.path("category").asText("").toLowerCase(Locale.ROOT);
            String severity = item.path("severity").asText("").toLowerCase(Locale.ROOT);
            String title = item.path("title").asText("");
            if (!paths.contains(path)) {
                errors.append("错误：文件不在本批评审范围内 ").append(path).append('\n');
                continue;
            }
            if (!CATEGORIES.contains(category) || !SEVERITIES.contains(severity) || title.isBlank()) {
                errors.append("错误：发现缺少合法的类别、级别或标题\n");
                continue;
            }
            if (!(item instanceof ObjectNode node)) {
                errors.append("错误：发现必须是对象\n");
                continue;
            }
            ObjectNode copy = node.deepCopy();
            if ("business".equals(category) && !item.path("kbRefs").isArray()) {
                // 业务发现没有知识库引用时标成弱证据，仍然收下
                copy.put("evidenceStrength", 2);
            }
            if ("database".equals(category) && !item.path("dbRefs").isArray()) {
                copy.put("evidenceStrength", 2);
            }
            findings.add(copy);
            accepted++;
        }
        if (accepted == 0) {
            return errors.toString().trim();
        }
        return "已收下 " + accepted + " 条发现" + (errors.isEmpty() ? "" : "\n" + errors);
    }

    public String finish() {
        done = true;
        return "已结束，请停止继续调用工具";
    }

    public boolean isDone() {
        return done;
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    public List<ObjectNode> findings() {
        return List.copyOf(findings);
    }

    public synchronized void absorb(FindingCollector other) {
        findings.addAll(other.findings);
    }

    public void reopen() {
        done = false;
    }
}
