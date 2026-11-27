package com.company.crbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

public final class ReviewFactFilter {

    public static final String PROMPT = """
            你是事实核查员。默认保留全部发现。
            只有当 diff 能证明发现是错的，才把它的 id 放进 remove。
            证明方式只有两种：发现指向的文件 diff 里根本没有 existingCode；或 diff 里的同一行文字与发现相反。
            安全、数据库结构、并发、内存类发现一律保留。价值高低不是删除理由。
            只输出 JSON：{"remove":["id"]}。没有要删的就输出 {"remove":[]}。
            """;

    private ReviewFactFilter() {
    }

    public static boolean protectedFinding(JsonNode finding) {
        if (finding.path("locked").asBoolean(false)) {
            return true;
        }
        String category = finding.path("category").asText("");
        if ("security".equals(category) || "database".equals(category)) {
            return true;
        }
        String title = finding.path("title").asText("");
        return title.contains("并发") || title.contains("内存");
    }

    public static Set<String> removableIds(JsonNode findings) {
        Set<String> ids = new HashSet<>();
        if (!findings.isArray()) {
            return ids;
        }
        for (JsonNode finding : findings) {
            if (!protectedFinding(finding)) {
                String id = finding.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    public static int remove(ObjectNode root, Set<String> removeIds) {
        if (removeIds.isEmpty()) {
            return 0;
        }
        int removed = removeArray(root.path("findings"), removeIds);
        for (JsonNode dimension : root.path("dimensions")) {
            if (dimension instanceof ObjectNode) {
                removeArray(dimension.path("findings"), removeIds);
            }
        }
        return removed;
    }

    private static int removeArray(JsonNode findings, Set<String> removeIds) {
        if (!(findings instanceof ArrayNode array)) {
            return 0;
        }
        int removed = 0;
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonNode finding = array.get(i);
            if (protectedFinding(finding)) {
                continue;
            }
            if (removeIds.contains(finding.path("id").asText(""))) {
                array.remove(i);
                removed++;
            }
        }
        return removed;
    }
}
