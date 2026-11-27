package com.company.crbot.gitlab;

import com.company.crbot.common.ApiException;
import com.company.crbot.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class GitlabClient {

    private final RestClient.Builder restClientBuilder;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public GitlabClient(RestClient.Builder restClientBuilder, SettingsService settingsService, ObjectMapper objectMapper) {
        this.restClientBuilder = restClientBuilder;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    public DiffBundle fetchPushDiff(long projectId, String beforeSha, String afterSha) {
        // 新分支的 before 全 0 时 compare 无起点，改用 commit diff
        if (beforeSha == null || beforeSha.matches("0+")) {
            return new DiffBundle(null, null, readDiffArray(get(projectId, "/repository/commits/" + afterSha + "/diff")));
        }
        JsonNode root = get(projectId, "/repository/compare?from=" + beforeSha + "&to=" + afterSha);
        JsonNode diffs = root.has("diffs") ? root.get("diffs") : root.get("changes");
        return new DiffBundle(null, null, readDiffItems(diffs));
    }

    public String fetchFile(long projectId, String path, String ref) {
        if (path == null || path.isBlank() || ref == null || ref.isBlank()) {
            throw new ApiException("缺少文件路径或提交号，不能读取实体");
        }
        // GitLab 要求文件路径整体编码，斜杠也要变成 %2F
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
        return exchange(projectId, "/repository/files/" + encoded + "/raw?ref=" + ref, null);
    }

    public DiffBundle fetchMergeRequestDiff(long projectId, int mrIid) {
        // GitLab 11.8 使用 merge_requests/:iid/changes，不走新版 GraphQL
        JsonNode root = get(projectId, "/merge_requests/" + mrIid + "/changes");
        return new DiffBundle(root.path("title").asText(null), root.path("description").asText(null),
                readDiffItems(root.get("changes")));
    }

    public boolean tryInlineComment(long projectId, int mrIid, String body, String path, int line, String beforeSha, String afterSha) {
        if (path == null || path.isBlank() || line <= 0 || afterSha == null || afterSha.isBlank()) {
            return false;
        }
        try {
            JsonNode mergeRequest = get(projectId, "/merge_requests/" + mrIid);
            JsonNode refs = mergeRequest.path("diff_refs");
            String base = refs.path("base_sha").asText(beforeSha == null ? "" : beforeSha);
            String start = refs.path("start_sha").asText(base);
            String head = refs.path("head_sha").asText(afterSha);
            if (base.isBlank() || head.isBlank()) {
                return false;
            }
            String payload = objectMapper.createObjectNode()
                    .put("body", body)
                    .set("position", objectMapper.createObjectNode()
                            .put("position_type", "text")
                            .put("base_sha", base)
                            .put("start_sha", start)
                            .put("head_sha", head)
                            .put("new_path", path)
                            .put("new_line", line))
                    .toString();
            exchange(projectId, "/merge_requests/" + mrIid + "/discussions", payload);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public void postMergeRequestNote(long projectId, int mrIid, String body) {
        String payload;
        try {
            payload = objectMapper.createObjectNode().put("body", body).toString();
        } catch (Exception ex) {
            throw new ApiException("组装 MR 评论失败");
        }
        exchange(projectId, "/merge_requests/" + mrIid + "/notes", payload);
    }

    public boolean noteExists(long projectId, int mrIid, String marker) {
        JsonNode notes = get(projectId, "/merge_requests/" + mrIid + "/notes?per_page=100");
        if (!notes.isArray()) {
            return false;
        }
        for (JsonNode note : notes) {
            if (note.path("body").asText("").contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public List<RepoProject> listProjects() {
        JsonNode root = readRoot(getRoot("/projects?per_page=100&simple=true&order_by=path&sort=asc"));
        List<RepoProject> projects = new ArrayList<>();
        if (!root.isArray()) {
            return projects;
        }
        for (JsonNode item : root) {
            String path = item.path("path_with_namespace").asText("");
            // 下拉只收有路径的仓库
            if (path.isBlank()) {
                continue;
            }
            projects.add(new RepoProject(item.path("id").asLong(), path));
        }
        return projects;
    }

    public List<String> listBranches(long projectId) {
        JsonNode root = readRoot(getRoot("/projects/" + projectId + "/repository/branches?per_page=100"));
        List<String> branches = new ArrayList<>();
        if (!root.isArray()) {
            return branches;
        }
        for (JsonNode item : root) {
            String name = item.path("name").asText("");
            // 空名字不能当订阅分支
            if (name.isBlank()) {
                continue;
            }
            branches.add(name);
        }
        return branches;
    }

    public List<GitUser> listUsers() {
        JsonNode root = readRoot(getRoot("/users?per_page=100&active=true"));
        List<GitUser> users = new ArrayList<>();
        if (!root.isArray()) {
            return users;
        }
        for (JsonNode item : root) {
            String username = item.path("username").asText("");
            // 停用账号不能再绑开发人员
            if (username.isBlank() || "blocked".equals(item.path("state").asText())) {
                continue;
            }
            users.add(new GitUser(item.path("id").asLong(), username, item.path("name").asText(username)));
        }
        return users;
    }

    public GitUser requireUser(long userId) {
        for (GitUser user : listUsers()) {
            if (user.id() == userId) {
                return user;
            }
        }
        throw new ApiException("Git 账号不存在");
    }

    private String getRoot(String pathAndQuery) {
        String baseUrl = settingsService.gitlabBaseUrl();
        String token = settingsService.gitlabToken();
        if (baseUrl.isBlank() || token.isBlank()) {
            throw new ApiException("未配置 GITLAB_BASE_URL 或 GITLAB_TOKEN");
        }
        String url = trimSlash(baseUrl) + "/api/v4" + pathAndQuery;
        return restClientBuilder.build()
                .get()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                    throw new ApiException("GitLab API 失败: " + httpResponse.getStatusCode().value());
                })
                .body(String.class);
    }

    private JsonNode readRoot(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new ApiException("解析 GitLab 响应失败");
        }
    }

    private JsonNode get(long projectId, String pathAndQuery) {
        String body = exchange(projectId, pathAndQuery, null);
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new ApiException("解析 GitLab 响应失败");
        }
    }

    private String exchange(long projectId, String pathAndQuery, String jsonBody) {
        String baseUrl = settingsService.gitlabBaseUrl();
        String token = settingsService.gitlabToken();
        if (baseUrl.isBlank() || token.isBlank()) {
            throw new ApiException("未配置 GITLAB_BASE_URL 或 GITLAB_TOKEN");
        }
        String url = trimSlash(baseUrl) + "/api/v4/projects/" + projectId + pathAndQuery;
        RestClient.RequestBodySpec spec = restClientBuilder.build()
                .method(jsonBody == null ? org.springframework.http.HttpMethod.GET : org.springframework.http.HttpMethod.POST)
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON);
        RestClient.ResponseSpec response = jsonBody == null ? spec.retrieve() : spec.body(jsonBody).retrieve();
        return response.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
            throw new ApiException("GitLab API 失败: " + httpResponse.getStatusCode().value());
        }).body(String.class);
    }

    private List<DiffFile> readDiffArray(JsonNode node) {
        return readDiffItems(node);
    }

    private List<DiffFile> readDiffItems(JsonNode node) {
        List<DiffFile> files = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return files;
        }
        for (JsonNode item : node) {
            String path = item.path("new_path").asText(item.path("old_path").asText(""));
            files.add(new DiffFile(path, item.path("old_path").asText(""), item.path("diff").asText(""),
                    item.path("deleted_file").asBoolean(false), false));
        }
        return files;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record DiffFile(String path, String oldPath, String patch, boolean deleted, boolean truncated) {
    }

    public record DiffBundle(String title, String description, List<DiffFile> files) {
    }

    public record RepoProject(long id, String path) {
    }

    public record GitUser(long id, String username, String name) {
    }
}
