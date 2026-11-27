package com.company.crbot.knowledge;

import com.company.crbot.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class KnowledgeFiles {

    private KnowledgeFiles() {
    }

    public static String read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            byte[] bytes = file.getBytes();
            String text;
            if (name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".markdown")) {
                text = new String(bytes, StandardCharsets.UTF_8);
            } else if (name.endsWith(".html") || name.endsWith(".htm")) {
                // 原型页面只保留可见文字，避免标签干扰检索
                text = htmlText(new String(bytes, StandardCharsets.UTF_8));
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                text = excelText(bytes);
            } else if (name.endsWith(".xmind")) {
                // 测试用例思维导图只抽取节点标题和备注
                text = xmindText(bytes);
            } else {
                throw new ApiException("只允许上传 md、txt、html、xls、xlsx、xmind 文件");
            }
            if (text.indexOf('\0') >= 0) {
                throw new ApiException("文件不是可读取的文本");
            }
            if (text.isBlank()) {
                throw new ApiException("文件内容为空");
            }
            return text;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("读取上传文件失败");
        }
    }

    private static String htmlText(String html) {
        String withoutScript = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        String withoutStyle = withoutScript.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        String text = withoutStyle.replaceAll("(?is)<[^>]+>", " ");
        return text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String excelText(byte[] bytes) throws Exception {
        DataFormatter formatter = new DataFormatter();
        StringBuilder builder = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                builder.append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell).trim();
                        if (!value.isBlank()) {
                            builder.append(value).append('\t');
                        }
                    }
                    builder.append('\n');
                }
            }
        }
        return builder.toString().trim();
    }

    public static String xmindText(byte[] bytes) throws Exception {
        String json = null;
        String xml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                // 新版导图是 content.json，旧版是 content.xml
                if (entryName.endsWith("content.json")) {
                    json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (entryName.endsWith("content.xml")) {
                    xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        if (json != null) {
            return topicsFromJson(json);
        }
        if (xml != null) {
            return topicsFromXml(xml);
        }
        throw new ApiException("XMind 文件里没有思维导图内容");
    }

    private static String topicsFromJson(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        StringBuilder builder = new StringBuilder();
        walkTopic(root, builder, 0);
        return builder.toString().trim();
    }

    private static void walkTopic(JsonNode node, StringBuilder builder, int depth) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walkTopic(child, builder, depth);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        // 画布同时有名称和 rootTopic，业务节点在 rootTopic 里，不能停在画布名
        if (node.has("rootTopic")) {
            appendTitle(node.get("title"), builder, depth);
            walkTopic(node.get("rootTopic"), builder, depth);
            return;
        }
        appendTitle(node.get("title"), builder, depth);
        appendNote(node, builder, depth);
        JsonNode children = node.get("children");
        if (children != null && children.isObject()) {
            // attached、detached、summary、callout 都是分支上的业务文字
            children.fields().forEachRemaining(entry -> walkTopic(entry.getValue(), builder, depth + 1));
        }
        // 外层包装没有主题时，继续找 sheets
        if (!node.has("title") && !node.has("children") && node.has("sheets")) {
            walkTopic(node.get("sheets"), builder, depth);
        }
    }

    private static void appendTitle(JsonNode title, StringBuilder builder, int depth) {
        String text = titleText(title);
        if (text.isBlank()) {
            return;
        }
        builder.append("  ".repeat(Math.max(depth, 0))).append(text).append('\n');
    }

    private static String titleText(JsonNode title) {
        if (title == null || title.isNull()) {
            return "";
        }
        if (title.isTextual()) {
            return title.asText().trim();
        }
        // 少数导图把标题做成对象
        if (title.isObject()) {
            String text = title.path("text").asText("").trim();
            if (!text.isBlank()) {
                return text;
            }
            return title.path("content").asText("").trim();
        }
        return "";
    }

    private static void appendNote(JsonNode node, StringBuilder builder, int depth) {
        String note = node.path("notes").path("plain").path("content").asText("").trim();
        if (note.isBlank()) {
            return;
        }
        builder.append("  ".repeat(depth + 1)).append(note).append('\n');
    }

    private static String topicsFromXml(String xml) {
        StringBuilder builder = new StringBuilder();
        java.util.regex.Matcher titles = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(xml);
        while (titles.find()) {
            String title = plainXml(titles.group(1));
            if (!title.isBlank()) {
                builder.append(title).append('\n');
            }
        }
        java.util.regex.Matcher notes = java.util.regex.Pattern.compile("(?is)<plain[^>]*>(.*?)</plain>").matcher(xml);
        while (notes.find()) {
            String note = plainXml(notes.group(1));
            if (!note.isBlank()) {
                builder.append(note).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private static String plainXml(String raw) {
        String text = raw.replaceAll("(?is)<!\\[CDATA\\[(.*?)]]>", "$1");
        return text.replaceAll("<[^>]+>", "").trim();
    }

    public static String extension(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }
}
