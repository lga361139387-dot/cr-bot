package com.company.crbot.rag;

import java.util.ArrayList;
import java.util.List;

public final class Chunker {

    // 当前向量模型单条输入上限 512 token。中文大约一字一 token，片长控制在 420 字以内。
    private static final int WINDOW = 420;
    private static final int OVERLAP = 40;
    private static final int MIN_PACK = 180;
    private static final int MAX_PACK = 420;

    private Chunker() {
    }

    public static List<String> chunkStandard(String text) {
        // 规范按标题切，再把过短条款合并、过长条款滑窗
        return pack(splitByHeading(text));
    }

    public static List<String> chunkKnowledge(String docType, String text) {
        // 纪要优先按议题或时间行切开，其余按标题
        if ("meeting".equals(docType)) {
            return pack(splitMeeting(text));
        }
        if (looksLikeTable(text)) {
            // 表格按行成片，表头跟到每一行，避免一条用例被从中间切开
            return pack(splitTable(text));
        }
        return pack(splitByHeading(text));
    }

    private static List<String> pack(List<String> sections) {
        List<String> packed = new ArrayList<>();
        StringBuilder bucket = new StringBuilder();
        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }
            // 单段已经超长时先把已有桶落盘，再滑窗
            if (section.length() > MAX_PACK) {
                flush(packed, bucket);
                packed.addAll(window(section, WINDOW, OVERLAP));
                continue;
            }
            if (bucket.length() > 0 && bucket.length() + section.length() + 1 > MAX_PACK) {
                flush(packed, bucket);
            }
            if (bucket.length() > 0) {
                bucket.append('\n');
            }
            bucket.append(section);
            if (bucket.length() >= MIN_PACK) {
                flush(packed, bucket);
            }
        }
        flush(packed, bucket);
        return packed;
    }

    private static void flush(List<String> packed, StringBuilder bucket) {
        if (bucket.isEmpty()) {
            return;
        }
        packed.add(bucket.toString().trim());
        bucket.setLength(0);
    }

    public static List<String> window(String text, int size, int overlap) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            parts.add(text.substring(start, end).trim());
            if (end == text.length()) {
                break;
            }
            int next = end - overlap;
            // 防止重叠参数异常导致死循环
            if (next <= start) {
                next = end;
            }
            start = next;
        }
        return parts;
    }

    private static boolean looksLikeTable(String text) {
        if (text == null || !text.contains("\t")) {
            return false;
        }
        int rows = 0;
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (line.contains("\t") && !line.isBlank()) {
                rows++;
            }
        }
        return rows >= 2;
    }

    private static List<String> splitTable(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        String header = "";
        List<String> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (header.isEmpty()) {
                header = line.trim();
                continue;
            }
            // 表头和本行放在一起，检索时还能知道每列是什么
            rows.add(header + "\n" + line.trim());
        }
        if (rows.isEmpty() && !header.isEmpty()) {
            rows.add(header);
        }
        return rows;
    }

    private static List<String> splitByHeading(String text) {
        String normalized = text.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.matches("^#{1,6}\\s+.*") && current.length() > 0) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            sections.add(current.toString().trim());
        }
        if (sections.isEmpty() && !normalized.isBlank()) {
            sections.add(normalized.trim());
        }
        return sections;
    }

    private static List<String> splitMeeting(String text) {
        String normalized = text.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            boolean boundary = line.matches("^#{1,6}\\s+.*")
                    || line.matches("^议题[:：].*")
                    || line.matches("^\\d{1,2}:\\d{2}.*");
            if (boundary && current.length() > 0) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0) {
            sections.add(current.toString().trim());
        }
        return sections;
    }
}
