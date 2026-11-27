package com.company.crbot.review;

import com.company.crbot.gitlab.GitlabClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DiffBatcher {

    // 单次请求留给 diff 的字数。系统提示词和回复还要占上下文，超出的文件下一批再看。
    public static final int CONTEXT_CHARS = 6000;

    private DiffBatcher() {
    }

    public static BatchPlan packByContext(List<GitlabClient.DiffFile> files) {
        List<GitlabClient.DiffFile> pieces = new ArrayList<>();
        for (GitlabClient.DiffFile file : files) {
            String patch = file.patch() == null ? "" : file.patch();
            if (patch.length() <= CONTEXT_CHARS) {
                pieces.add(file);
                continue;
            }
            // 单个文件超过一次上下文时拆开，每一段都会评审，不跳过
            for (String part : com.company.crbot.rag.Chunker.window(patch, CONTEXT_CHARS, 200)) {
                pieces.add(new GitlabClient.DiffFile(file.path(), file.oldPath(), part, file.deleted(), false));
            }
        }
        List<List<GitlabClient.DiffFile>> batches = new ArrayList<>();
        List<GitlabClient.DiffFile> current = new ArrayList<>();
        int used = 0;
        Set<Integer> taken = new LinkedHashSet<>();
        for (int i = 0; i < pieces.size(); i++) {
            if (taken.contains(i)) {
                continue;
            }
            GitlabClient.DiffFile file = pieces.get(i);
            int length = file.patch() == null ? 0 : file.patch().length();
            if (!current.isEmpty() && used + length > CONTEXT_CHARS) {
                batches.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(file);
            taken.add(i);
            used += length;
            int partnerIndex = partnerIndex(file, pieces, taken);
            if (partnerIndex >= 0) {
                GitlabClient.DiffFile partner = pieces.get(partnerIndex);
                int partnerLength = partner.patch() == null ? 0 : partner.patch().length();
                if (used + partnerLength <= CONTEXT_CHARS) {
                    current.add(partner);
                    taken.add(partnerIndex);
                    used += partnerLength;
                }
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return new BatchPlan(batches, List.of());
    }

    private static int partnerIndex(GitlabClient.DiffFile file, List<GitlabClient.DiffFile> files, Set<Integer> taken) {
        String wanted = pairPath(file.path() == null ? "" : file.path());
        if (wanted == null) {
            return -1;
        }
        for (int i = 0; i < files.size(); i++) {
            if (!taken.contains(i) && wanted.equals(files.get(i).path())) {
                return i;
            }
        }
        return -1;
    }

    public static BatchPlan plan(List<GitlabClient.DiffFile> files, int groupSize, int maxFileBytes) {
        List<SkippedFile> skipped = new ArrayList<>();
        List<GitlabClient.DiffFile> kept = new ArrayList<>();
        int limit = Math.max(groupSize, 1);
        for (GitlabClient.DiffFile file : files) {
            String patch = file.patch() == null ? "" : file.patch();
            int bytes = patch.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxFileBytes) {
                // 超大文件不截断后假装审完，单独记为跳过
                skipped.add(new SkippedFile(file.path(), "too_large"));
                continue;
            }
            kept.add(file);
        }
        List<List<GitlabClient.DiffFile>> batches = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (GitlabClient.DiffFile file : kept) {
            if (used.contains(file.path())) {
                continue;
            }
            List<GitlabClient.DiffFile> group = new ArrayList<>();
            group.add(file);
            used.add(file.path());
            GitlabClient.DiffFile partner = partner(file, kept, used);
            if (partner != null) {
                group.add(partner);
                used.add(partner.path());
            }
            String dir = directory(file.path());
            for (GitlabClient.DiffFile other : kept) {
                if (group.size() >= limit || used.contains(other.path())) {
                    continue;
                }
                if (dir.equals(directory(other.path()))) {
                    group.add(other);
                    used.add(other.path());
                }
            }
            batches.add(group);
        }
        return new BatchPlan(batches, skipped);
    }

    public static BatchPlan arrange(List<GitlabClient.DiffFile> files, List<List<String>> groups, int maxFileBytes) {
        BatchPlan base = plan(files, Math.max(files.size(), 1), maxFileBytes);
        Map<String, GitlabClient.DiffFile> byPath = new java.util.LinkedHashMap<>();
        for (List<GitlabClient.DiffFile> batch : base.batches()) {
            for (GitlabClient.DiffFile file : batch) {
                byPath.put(file.path(), file);
            }
        }
        List<List<GitlabClient.DiffFile>> arranged = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (List<String> group : groups) {
            List<GitlabClient.DiffFile> batch = new ArrayList<>();
            for (String path : group) {
                GitlabClient.DiffFile file = byPath.get(path);
                if (file == null || used.contains(path)) {
                    continue;
                }
                batch.add(file);
                used.add(path);
            }
            if (!batch.isEmpty()) {
                arranged.add(batch);
            }
        }
        for (GitlabClient.DiffFile file : byPath.values()) {
            if (!used.contains(file.path())) {
                // 模型没提到的文件仍然单独成批，不能丢掉
                arranged.add(List.of(file));
            }
        }
        return new BatchPlan(arranged, base.skipped());
    }

    public static int changedLines(List<GitlabClient.DiffFile> files) {
        int count = 0;
        for (GitlabClient.DiffFile file : files) {
            String patch = file.patch() == null ? "" : file.patch();
            for (String line : patch.split("\\R")) {
                if ((line.startsWith("+") || line.startsWith("-")) && !line.startsWith("+++") && !line.startsWith("---")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static GitlabClient.DiffFile partner(GitlabClient.DiffFile file, List<GitlabClient.DiffFile> files, Set<String> used) {
        String path = file.path() == null ? "" : file.path();
        String wanted = pairPath(path);
        if (wanted == null) {
            return null;
        }
        for (GitlabClient.DiffFile other : files) {
            if (!used.contains(other.path()) && wanted.equals(other.path())) {
                return other;
            }
        }
        return null;
    }

    static String pairPath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.endsWith("Mapper.java")) {
            return normalized.substring(0, normalized.length() - "Mapper.java".length()) + "Mapper.xml";
        }
        if (normalized.endsWith("Mapper.xml")) {
            return normalized.substring(0, normalized.length() - "Mapper.xml".length()) + "Mapper.java";
        }
        if (normalized.endsWith("Service.java")) {
            return normalized.substring(0, normalized.length() - "Service.java".length()) + "ServiceImpl.java";
        }
        if (normalized.endsWith("ServiceImpl.java")) {
            return normalized.substring(0, normalized.length() - "ServiceImpl.java".length()) + "Service.java";
        }
        return null;
    }

    private static String directory(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        return normalized.substring(0, slash).toLowerCase(Locale.ROOT);
    }

    public record SkippedFile(String path, String reason) {
    }

    public record BatchPlan(List<List<GitlabClient.DiffFile>> batches, List<SkippedFile> skipped) {
    }
}
