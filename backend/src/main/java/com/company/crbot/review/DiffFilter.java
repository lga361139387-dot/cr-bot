package com.company.crbot.review;

import com.company.crbot.gitlab.GitlabClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DiffFilter {

    private static final Set<String> ALLOWED = Set.of("java", "xml", "yml", "yaml", "sql", "ts", "vue", "js", "kt");

    private DiffFilter() {
    }

    public static List<GitlabClient.DiffFile> filter(List<GitlabClient.DiffFile> files) {
        return partition(files).kept();
    }

    public static Result partition(List<GitlabClient.DiffFile> files) {
        List<GitlabClient.DiffFile> kept = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (GitlabClient.DiffFile file : files) {
            if (file.path() == null || file.path().isBlank()) {
                skipped.add(new Skipped("", "没有路径"));
                continue;
            }
            String name = file.path().toLowerCase(Locale.ROOT);
            // 锁文件和压缩脚本不参与评审
            if (name.endsWith(".min.js") || name.endsWith("package-lock.json") || name.endsWith("yarn.lock")
                    || name.endsWith("pnpm-lock.yaml") || name.endsWith("gemfile.lock")) {
                skipped.add(new Skipped(file.path(), "锁文件或压缩脚本"));
                continue;
            }
            int dot = name.lastIndexOf('.');
            if (dot < 0 || !ALLOWED.contains(name.substring(dot + 1))) {
                skipped.add(new Skipped(file.path(), "扩展名不在评审范围"));
                continue;
            }
            kept.add(file);
        }
        return new Result(kept, skipped);
    }

    public record Skipped(String path, String reason) {
    }

    public record Result(List<GitlabClient.DiffFile> kept, List<Skipped> skipped) {
    }

    public static List<GitlabClient.DiffFile> truncate(List<GitlabClient.DiffFile> files, int maxBytes) {
        List<GitlabClient.DiffFile> limited = new ArrayList<>();
        int used = 0;
        for (GitlabClient.DiffFile file : files) {
            String patch = file.patch() == null ? "" : file.patch();
            byte[] bytes = patch.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (used >= maxBytes) {
                limited.add(new GitlabClient.DiffFile(file.path(), file.oldPath(), "/* CRBOT: diff truncated */", file.deleted(), true));
                continue;
            }
            if (used + bytes.length > maxBytes) {
                int remain = Math.max(0, maxBytes - used);
                String cut = new String(bytes, 0, Math.min(remain, bytes.length), java.nio.charset.StandardCharsets.UTF_8);
                limited.add(new GitlabClient.DiffFile(file.path(), file.oldPath(), cut + "\n/* CRBOT: diff truncated */", file.deleted(), true));
                used = maxBytes;
                continue;
            }
            used += bytes.length;
            limited.add(file);
        }
        return limited;
    }
}
