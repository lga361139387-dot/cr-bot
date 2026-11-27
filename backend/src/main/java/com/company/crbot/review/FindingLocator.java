package com.company.crbot.review;

import com.company.crbot.gitlab.GitlabClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FindingLocator {

    public static final short RESOLVED = 1;
    public static final short UNRESOLVED = 2;
    public static final short SKIPPED = 3;
    public static final short STRONG = 1;
    public static final short WEAK = 2;

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private FindingLocator() {
    }

    public static void apply(JsonNode root, List<GitlabClient.DiffFile> files) {
        if (!(root instanceof ObjectNode object) || files == null) {
            return;
        }
        // 维度里的发现和拍平后的发现是两份拷贝，都要按证据重算行号
        locateArray(object.path("findings"), files);
        for (JsonNode dimension : object.path("dimensions")) {
            locateArray(dimension.path("findings"), files);
        }
    }

    public static Location locate(String filePath, String existingCode, String patch) {
        if (existingCode == null || existingCode.isBlank()) {
            // 没有可核对片段时，模型自报的行号不能当成精确定位
            return new Location(0, 0, SKIPPED, WEAK);
        }
        List<String> targets = normalizeLines(existingCode);
        if (targets.isEmpty() || patch == null || patch.isBlank()) {
            return new Location(0, 0, UNRESOLVED, WEAK);
        }
        for (Hunk hunk : parseHunks(patch)) {
            Location matched = matchSide(hunk, targets, true);
            if (matched != null) {
                return matched;
            }
        }
        for (Hunk hunk : parseHunks(patch)) {
            Location matched = matchSide(hunk, targets, false);
            if (matched != null) {
                return matched;
            }
        }
        return new Location(0, 0, UNRESOLVED, WEAK);
    }

    private static void locateArray(JsonNode findings, List<GitlabClient.DiffFile> files) {
        if (!findings.isArray()) {
            return;
        }
        for (JsonNode finding : findings) {
            if (!(finding instanceof ObjectNode item)) {
                continue;
            }
            String path = item.path("filePath").asText("");
            String existing = item.path("existingCode").asText("");
            String patch = patchOf(files, path);
            Location location = locate(path, existing, patch);
            writeLocation(item, location);
            if (location.status() == UNRESOLVED) {
                // 本文件对不上时，只在其他文件唯一命中才改路径
                relocateUnique(item, files);
            }
        }
    }

    private static void writeLocation(ObjectNode item, Location location) {
        item.put("startLine", location.startLine());
        item.put("endLine", location.endLine());
        item.put("locationStatus", location.status());
        item.put("evidenceStrength", location.strength());
    }

    static boolean relocateUnique(ObjectNode item, List<GitlabClient.DiffFile> files) {
        String existing = item.path("existingCode").asText("");
        String ownPath = item.path("filePath").asText("");
        if (existing.isBlank()) {
            return false;
        }
        String hitPath = null;
        Location hit = null;
        int hits = 0;
        for (GitlabClient.DiffFile file : files) {
            if (ownPath.equals(file.path())) {
                continue;
            }
            Location location = locate(file.path(), existing, file.patch());
            if (location.status() != RESOLVED) {
                continue;
            }
            hits++;
            hit = location;
            hitPath = file.path();
            if (hits > 1) {
                // 多处都能对上就不动，避免把证据改挂到错误文件
                return false;
            }
        }
        if (hits != 1 || hit == null) {
            return false;
        }
        item.put("filePath", hitPath);
        writeLocation(item, hit);
        return true;
    }

    private static String patchOf(List<GitlabClient.DiffFile> files, String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        for (GitlabClient.DiffFile file : files) {
            if (path.equals(file.path()) || path.equals(file.oldPath())) {
                return file.patch() == null ? "" : file.patch();
            }
        }
        return "";
    }

    private static Location matchSide(Hunk hunk, List<String> targets, boolean newSide) {
        List<IndexedLine> side = new ArrayList<>();
        int oldLine = hunk.oldStart;
        int newLine = hunk.newStart;
        for (HunkLine line : hunk.lines) {
            if (line.kind == ' ') {
                side.add(new IndexedLine(newSide ? newLine : oldLine, normalizeLine(line.text)));
                oldLine++;
                newLine++;
            } else if (line.kind == '+') {
                if (newSide) {
                    side.add(new IndexedLine(newLine, normalizeLine(line.text)));
                }
                newLine++;
            } else if (line.kind == '-') {
                if (!newSide) {
                    side.add(new IndexedLine(oldLine, normalizeLine(line.text)));
                }
                oldLine++;
            }
        }
        if (side.size() < targets.size()) {
            return null;
        }
        for (int i = 0; i <= side.size() - targets.size(); i++) {
            boolean matched = true;
            for (int j = 0; j < targets.size(); j++) {
                if (!side.get(i + j).text.equals(targets.get(j))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                int start = side.get(i).line;
                int end = side.get(i + targets.size() - 1).line;
                return new Location(start, end, RESOLVED, STRONG);
            }
        }
        return null;
    }

    static List<Hunk> parseHunks(String patch) {
        List<Hunk> hunks = new ArrayList<>();
        Hunk current = null;
        for (String raw : patch.split("\\R")) {
            Matcher matcher = HUNK.matcher(raw);
            if (matcher.find()) {
                current = new Hunk(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(3)));
                hunks.add(current);
                continue;
            }
            if (current == null || raw.startsWith("diff --git ") || raw.startsWith("\\ No newline")) {
                continue;
            }
            if (raw.startsWith("+")) {
                current.lines.add(new HunkLine('+', raw.substring(1)));
            } else if (raw.startsWith("-")) {
                current.lines.add(new HunkLine('-', raw.substring(1)));
            } else if (raw.startsWith(" ")) {
                current.lines.add(new HunkLine(' ', raw.substring(1)));
            }
        }
        return hunks;
    }

    private static List<String> normalizeLines(String code) {
        List<String> lines = new ArrayList<>();
        for (String raw : code.split("\\R")) {
            String line = normalizeLine(raw);
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String normalizeLine(String line) {
        String text = line.replace("\r", "").trim();
        if (text.startsWith("+") || text.startsWith("-")) {
            text = text.substring(1).trim();
        }
        return text;
    }

    public record Location(int startLine, int endLine, short status, short strength) {
    }

    private static final class Hunk {
        private final int oldStart;
        private final int newStart;
        private final List<HunkLine> lines = new ArrayList<>();

        private Hunk(int oldStart, int newStart) {
            this.oldStart = oldStart;
            this.newStart = newStart;
        }
    }

    private static final class HunkLine {
        private final char kind;
        private final String text;

        private HunkLine(char kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static final class IndexedLine {
        private final int line;
        private final String text;

        private IndexedLine(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }
}
