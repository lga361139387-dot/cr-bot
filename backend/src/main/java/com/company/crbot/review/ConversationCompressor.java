package com.company.crbot.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ConversationCompressor {

    private ConversationCompressor() {
    }

    public static ArrayNode compress(ArrayNode messages) {
        // 约 20 万 token 的 80%。中文按一字一个 token 估算
        return compress(messages, 160_000);
    }

    public static ArrayNode compress(ArrayNode messages, int budgetChars) {
        if (messages.size() <= 4) {
            return messages;
        }
        int frozen = Math.min(2, messages.size());
        int tail = messages.size();
        int suffix = 0;
        for (int i = messages.size() - 1; i >= frozen; i--) {
            int length = messages.get(i).toString().length();
            if (suffix + length > budgetChars) {
                break;
            }
            suffix += length;
            tail = i;
        }
        if (tail >= messages.size()) {
            // 最近一条也放不下时仍保留它，避免把工具结果全部摘要掉
            tail = messages.size() - 1;
        }
        while (tail > frozen && "tool".equals(messages.get(tail).path("role").asText())) {
            // 工具结果必须和它前面的模型回复留在一起
            tail--;
        }
        if (tail <= frozen) {
            return messages;
        }
        ArrayNode kept = messages.arrayNode();
        for (int i = 0; i < frozen; i++) {
            kept.add(messages.get(i).deepCopy());
        }
        StringBuilder summary = new StringBuilder("已压缩的评审过程：\n");
        for (int i = frozen; i < tail; i++) {
            JsonNode message = messages.get(i);
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            summary.append(message.path("role").asText("")).append('：').append(limit(content, 240)).append('\n');
        }
        ObjectNode note = kept.addObject();
        note.put("role", "system");
        note.put("content", summary.toString());
        for (int i = tail; i < messages.size(); i++) {
            kept.add(messages.get(i).deepCopy());
        }
        return kept;
    }

    private static String limit(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
