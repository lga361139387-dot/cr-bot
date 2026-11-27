package com.company.crbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

public final class ReviewResultParser {

    private static final Set<String> SEVERITIES = Set.of("info", "low", "medium", "high");
    private static final Set<String> CATEGORIES = Set.of("standard", "quality", "performance", "security", "business", "database");
    private static final Set<String> ALIGNMENT = Set.of("matched", "unmatched", "skipped");

    private ReviewResultParser() {
    }

    public static JsonNode parse(String content, ObjectMapper objectMapper) {
        String json = unwrap(content);
        try {
            JsonNode root = objectMapper.readTree(json);
            validate(root);
            // 规范不符和性能问题固定为高危，不采用模型给出的更低级别
            return applyFixedSeverity(root);
        } catch (ReviewParseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReviewParseException("模型输出不是合法 JSON");
        }
    }

    public static void validate(JsonNode root) {
        if (!root.path("summary").isTextual()) {
            throw new ReviewParseException("缺少 summary");
        }
        if (!SEVERITIES.contains(root.path("overallSeverity").asText())) {
            throw new ReviewParseException("overallSeverity 不合法");
        }
        JsonNode alignment = root.path("businessAlignment");
        if (!ALIGNMENT.contains(alignment.path("status").asText())) {
            throw new ReviewParseException("businessAlignment.status 不合法");
        }
        if (!alignment.path("reason").isTextual()) {
            throw new ReviewParseException("缺少 businessAlignment.reason");
        }
        if (!root.path("findings").isArray()) {
            throw new ReviewParseException("findings 必须是数组");
        }
        for (JsonNode finding : root.path("findings")) {
            if (!finding.path("title").isTextual() || finding.path("title").asText().isBlank()) {
                throw new ReviewParseException("finding 缺少 title");
            }
            if (!CATEGORIES.contains(finding.path("category").asText())) {
                throw new ReviewParseException("finding.category 不合法");
            }
                if (!SEVERITIES.contains(finding.path("severity").asText())) {
                    throw new ReviewParseException("finding.severity 不合法");
                }
                String category = finding.path("category").asText();
                if (!CATEGORIES.contains(category) && !"function".equals(category)) {
                    throw new ReviewParseException("finding.category 不合法");
                }
            }
    }

    static JsonNode applyFixedSeverity(JsonNode root) {
        if (!(root instanceof ObjectNode object)) {
            return root;
        }
        int highest = rank(object.path("overallSeverity").asText());
        for (JsonNode finding : object.path("findings")) {
            if (!(finding instanceof ObjectNode item)) {
                continue;
            }
            String category = item.path("category").asText();
            // 不符合编码规范、性能问题一律高危
            if ("standard".equals(category) || "performance".equals(category)) {
                item.put("severity", "high");
            }
            highest = Math.max(highest, rank(item.path("severity").asText()));
        }
        object.put("overallSeverity", nameOf(highest));
        return object;
    }

    public static JsonNode normalizeDimensions(JsonNode root, boolean businessHit, boolean functionHit) {
        if (!(root instanceof ObjectNode object)) {
            return root;
        }
        com.fasterxml.jackson.databind.node.ArrayNode rebuilt = object.arrayNode();
        rebuilt.add(dimensionNode(object, "quality", "代码质量", true, ""));
        rebuilt.add(dimensionNode(object, "business", "业务角度", businessHit, "未匹配到原型、会议纪要或业务逻辑，跳过业务维度"));
        rebuilt.add(dimensionNode(object, "function", "功能角度", functionHit, "未匹配到测试用例、概要设计或技术方案，跳过功能维度"));
        object.set("dimensions", rebuilt);
        com.fasterxml.jackson.databind.node.ArrayNode all = object.arrayNode();
        int highest = 0;
        boolean hasReviewedFinding = false;
        for (JsonNode dimension : rebuilt) {
            // 跳过的维度不计入综合发现
            if (!"reviewed".equals(dimension.path("status").asText())) {
                continue;
            }
            for (JsonNode finding : dimension.path("findings")) {
                if (!(finding instanceof ObjectNode item)) {
                    continue;
                }
                String category = item.path("category").asText();
                if ("standard".equals(category) || "performance".equals(category)) {
                    item.put("severity", "high");
                }
                item.put("dimension", dimension.path("code").asText());
                all.add(item);
                hasReviewedFinding = true;
                highest = Math.max(highest, rank(item.path("severity").asText()));
            }
        }
        object.set("findings", all);
        if (hasReviewedFinding) {
            object.put("overallSeverity", nameOf(highest));
        }
        return object;
    }

    private static ObjectNode dimensionNode(ObjectNode root, String code, String name, boolean available, String skipReason) {
        ObjectNode node = root.objectNode();
        node.put("code", code);
        node.put("name", name);
        JsonNode model = findDimension(root.path("dimensions"), code);
        if (!available) {
            node.put("status", "skipped");
            node.put("reason", skipReason);
            node.put("summary", "");
            node.put("severity", "info");
            node.putArray("findings");
            return node;
        }
        node.put("status", "reviewed");
        node.put("reason", "");
        String summary = model.path("summary").asText("");
        if (summary.isBlank() && "quality".equals(code)) {
            summary = root.path("summary").asText("");
        }
        node.put("summary", summary);
        node.put("severity", model.path("severity").asText("info"));
        com.fasterxml.jackson.databind.node.ArrayNode findings = node.putArray("findings");
        JsonNode modelFindings = model.path("findings");
        if (modelFindings.isArray() && !modelFindings.isEmpty()) {
            modelFindings.forEach(item -> findings.add(item.deepCopy()));
        } else {
            for (JsonNode finding : root.path("findings")) {
                if (belongs(code, finding.path("category").asText(), finding.path("dimension").asText(""))) {
                    findings.add(finding.deepCopy());
                }
            }
        }
        return node;
    }

    private static JsonNode findDimension(JsonNode dimensions, String code) {
        if (!dimensions.isArray()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        for (JsonNode dimension : dimensions) {
            if (code.equals(dimension.path("code").asText())) {
                return dimension;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static boolean belongs(String code, String category, String dimension) {
        if (!dimension.isBlank()) {
            return code.equals(dimension);
        }
        if ("quality".equals(code)) {
            return "standard".equals(category) || "performance".equals(category) || "quality".equals(category)
                    || "security".equals(category) || "database".equals(category);
        }
        if ("business".equals(code)) {
            return "business".equals(category);
        }
        return "function".equals(category);
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    private static String nameOf(int rank) {
        return switch (rank) {
            case 3 -> "high";
            case 2 -> "medium";
            case 1 -> "low";
            default -> "info";
        };
    }

    static String unwrap(String content) {
        if (content == null) {
            throw new ReviewParseException("模型输出为空");
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                trimmed = trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ReviewParseException("模型输出中没有 JSON 对象");
        }
        return trimmed.substring(start, end + 1);
    }

    public static final class ReviewParseException extends RuntimeException {
        public ReviewParseException(String message) {
            super(message);
        }
    }

    public record AlignmentFix(String status, String reason) {
    }

    public static AlignmentFix enforceAlignment(String modelStatus, String modelReason, boolean knowledgeHit) {
        // skipped 只能出现在知识库未命中时
        if (!knowledgeHit) {
            return new AlignmentFix("skipped", modelReason == null || modelReason.isBlank() ? "知识库未命中" : modelReason);
        }
        if ("skipped".equals(modelStatus)) {
            return new AlignmentFix("unmatched", "检索已命中知识库，但模型未给出比对结论");
        }
        return new AlignmentFix(modelStatus, modelReason);
    }

    public static List<String> severities() {
        return List.copyOf(SEVERITIES);
    }
}
