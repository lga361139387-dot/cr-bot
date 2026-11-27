package com.company.crbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ReportMarkdown {

    private ReportMarkdown() {
    }

    public static String render(JsonNode root, ObjectMapper objectMapper) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 摘要\n\n").append(root.path("summary").asText("")).append("\n\n");
        builder.append("总体级别：").append(root.path("overallSeverity").asText("")).append("\n\n");
        JsonNode alignment = root.path("businessAlignment");
        builder.append("## 业务相符性\n\n");
        builder.append(alignment.path("status").asText("")).append("：").append(alignment.path("reason").asText("")).append("\n\n");
        if (alignment.path("kbRefs").isArray()) {
            for (JsonNode ref : alignment.path("kbRefs")) {
                builder.append("- ").append(ref.path("title").asText(""))
                        .append("：").append(ref.path("snippet").asText("")).append('\n');
            }
            builder.append('\n');
        }
        builder.append("## 问题\n\n");
        for (JsonNode finding : root.path("findings")) {
            builder.append("### ").append(finding.path("title").asText("")).append("\n\n");
            builder.append(finding.path("severity").asText("")).append(" / ").append(finding.path("category").asText(""));
            builder.append(" / ").append(finding.path("filePath").asText(""));
            if (finding.path("locationStatus").asInt(0) == FindingLocator.RESOLVED && finding.path("startLine").asInt(0) > 0) {
                builder.append(':').append(finding.path("startLine").asInt());
            } else if (finding.path("locationStatus").asInt(0) == FindingLocator.UNRESOLVED) {
                builder.append("（未定位）");
            }
            builder.append("\n\n");
            if (!finding.path("existingCode").asText("").isBlank()) {
                builder.append("证据：\n```\n").append(finding.path("existingCode").asText("")).append("\n```\n\n");
            }
            builder.append(finding.path("detail").asText("")).append("\n\n");
            builder.append("建议：").append(finding.path("suggestion").asText("")).append("\n\n");
        }
        return builder.toString();
    }
}
