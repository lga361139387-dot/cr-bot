package com.company.crbot.review;

import com.company.crbot.common.ApiException;
import com.company.crbot.config.AppProperties;
import com.company.crbot.settings.SettingsService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class RepoWorkspace {

    private static final int MAX_READ_LINES = 500;
    private static final int MAX_MATCHES = 40;

    private final AppProperties properties;
    private final SettingsService settingsService;

    public RepoWorkspace(AppProperties properties, SettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
    }

    public Checkout sync(ReviewJobEntity job) {
        String sha = commitSha(job);
        String branch = job.getRefBranch();
        String projectPath = job.getProjectPath();
        if (job.getGitlabProjectId() == null || projectPath == null || projectPath.isBlank()) {
            throw new ApiException("缺少项目路径，不能更新本地代码");
        }
        if (branch == null || branch.isBlank()) {
            throw new ApiException("缺少分支，不能更新本地代码");
        }
        Path root = Path.of(properties.getRepoDir() == null || properties.getRepoDir().isBlank() ? "data/repos" : properties.getRepoDir())
                .toAbsolutePath().normalize();
        Path dir = root.resolve(String.valueOf(job.getGitlabProjectId())).normalize();
        if (!dir.startsWith(root)) {
            throw new ApiException("本地代码目录越界");
        }
        String remote = remoteUrl(projectPath);
        String header = "http.extraHeader=PRIVATE-TOKEN: " + settingsService.gitlabToken();
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(dir.resolve(".git"))) {
                if (Files.exists(dir)) {
                    throw new ApiException("本地目录不是 Git 仓库，不能更新代码");
                }
                // 每个项目只克隆一次，后面的评审在这份目录上更新
                run(root, header, 120, "clone", remote, dir.getFileName().toString());
            }
            run(dir, header, 120, "fetch", "origin", branch);
            run(dir, header, 30, "checkout", "--force", "--detach", sha);
            String head = run(dir, header, 15, "rev-parse", "HEAD").trim();
            if (!head.equals(sha)) {
                throw new ApiException("本地代码没有停在提交 " + sha);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("更新本地代码失败: " + ex.getMessage());
        }
        return new Checkout(dir, sha);
    }

    public String readSource(Checkout checkout, String path, int startLine, int endLine) {
        Path file = resolveInside(checkout.root(), path);
        if (!Files.isRegularFile(file)) {
            return "文件不存在";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return slice(path, lines, startLine, endLine);
        } catch (IOException ex) {
            throw new ApiException("读取源码失败: " + ex.getMessage());
        }
    }

    public String searchSource(Checkout checkout, String query, String path) {
        if (query == null || query.isBlank()) {
            return "缺少检索词";
        }
        if (query.contains("\n") || query.contains("\r")) {
            return "检索词不能换行";
        }
        List<String> args = new ArrayList<>();
        args.add("grep");
        args.add("-n");
        args.add("-I");
        args.add("-F");
        args.add("-e");
        args.add(query);
        if (path != null && !path.isBlank()) {
            resolveInside(checkout.root(), path);
            args.add("--");
            args.add(path.replace('\\', '/'));
        }
        String output = run(checkout.root(), null, 20, args.toArray(String[]::new));
        return limitLines(output, MAX_MATCHES, "没有匹配");
    }

    public String findSource(Checkout checkout, String name) {
        if (name == null || name.isBlank()) {
            return "缺少文件名";
        }
        String output = run(checkout.root(), null, 20, "ls-files");
        String needle = name.toLowerCase(Locale.ROOT);
        StringBuilder matched = new StringBuilder();
        int count = 0;
        for (String line : output.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                matched.append(line).append('\n');
                count++;
                if (count >= MAX_MATCHES) {
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            return "没有匹配的文件";
        }
        return matched.toString().trim();
    }

    static String commitSha(ReviewJobEntity job) {
        String sha = job.getAfterSha();
        if (sha == null || sha.isBlank() || sha.matches("0+")) {
            sha = job.getCommitSha();
        }
        if (sha == null || sha.isBlank() || sha.matches("0+")) {
            throw new ApiException("缺少提交号，不能更新本地代码");
        }
        return sha;
    }

    public static Path resolveInside(Path root, String path) {
        if (path == null || path.isBlank()) {
            throw new ApiException("缺少文件路径");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new ApiException("文件路径越界");
        }
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root)) {
            throw new ApiException("文件路径越界");
        }
        return file;
    }

    public static String slice(String path, List<String> lines, int startLine, int endLine) {
        int start = startLine <= 0 ? 1 : startLine;
        int end = endLine <= 0 ? lines.size() : endLine;
        if (end < start) {
            end = start;
        }
        boolean truncated = end - start + 1 > MAX_READ_LINES;
        if (truncated) {
            end = start + MAX_READ_LINES - 1;
        }
        if (start > lines.size()) {
            return "起始行超过文件末尾，共 " + lines.size() + " 行";
        }
        end = Math.min(end, lines.size());
        StringBuilder builder = new StringBuilder();
        builder.append("File: ").append(path).append(" (Total lines: ").append(lines.size()).append(")\n");
        builder.append("LINE_RANGE: ").append(start).append('-').append(end).append('\n');
        for (int i = start; i <= end; i++) {
            builder.append(i).append('|').append(lines.get(i - 1)).append('\n');
        }
        if (truncated) {
            builder.append("结果已截到 ").append(MAX_READ_LINES).append(" 行，请缩小行号范围\n");
        }
        return builder.toString();
    }

    private String remoteUrl(String projectPath) {
        String base = settingsService.gitlabBaseUrl();
        if (base == null || base.isBlank() || settingsService.gitlabToken() == null || settingsService.gitlabToken().isBlank()) {
            throw new ApiException("未配置 GitLab 地址或令牌，不能更新本地代码");
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/" + projectPath + ".git";
    }

    private String run(Path dir, String header, int timeoutSeconds, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        if (header != null) {
            command.add("-c");
            command.add(header);
        }
        if (dir != null && Files.isDirectory(dir.resolve(".git"))) {
            command.add("-C");
            command.add(dir.toString());
        }
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (dir != null && !Files.isDirectory(dir.resolve(".git"))) {
            builder.directory(dir.toFile());
        }
        try {
            Process process = builder.start();
            StringBuilder outputBuffer = new StringBuilder();
            StringBuilder errorBuffer = new StringBuilder();
            // 先把输出读走，避免 Git 把管道写满后一直等
            Thread outThread = new Thread(() -> drain(process.getInputStream(), outputBuffer));
            Thread errThread = new Thread(() -> drain(process.getErrorStream(), errorBuffer));
            outThread.start();
            errThread.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            outThread.join(1000);
            errThread.join(1000);
            String output = outputBuffer.toString();
            String error = errorBuffer.toString();
            if (!finished) {
                throw new ApiException("Git 命令超时");
            }
            if (process.exitValue() != 0) {
                // grep 没有命中时退出码是 1
                if ("grep".equals(args[0]) && process.exitValue() == 1) {
                    return "";
                }
                String message = (error.isBlank() ? output : error).trim();
                throw new ApiException(message.isBlank() ? "Git 命令失败" : message);
            }
            return output;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("执行 Git 失败: " + ex.getMessage());
        }
    }

    private static void drain(java.io.InputStream stream, StringBuilder buffer) {
        try {
            buffer.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            buffer.append(ex.getMessage());
        }
    }

    private static String limitLines(String output, int max, String empty) {
        if (output == null || output.isBlank()) {
            return empty;
        }
        String[] lines = output.split("\\R");
        StringBuilder builder = new StringBuilder();
        int count = Math.min(lines.length, max);
        for (int i = 0; i < count; i++) {
            builder.append(lines[i]).append('\n');
        }
        if (lines.length > max) {
            builder.append("只显示前 ").append(max).append(" 行\n");
        }
        return builder.toString().trim();
    }

    public record Checkout(Path root, String sha) {
    }
}
