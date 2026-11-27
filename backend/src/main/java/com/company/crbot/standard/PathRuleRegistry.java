package com.company.crbot.standard;

import com.company.crbot.gitlab.GitlabClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class PathRuleRegistry {

    private PathRuleRegistry() {
    }

    public static String checklist(List<GitlabClient.DiffFile> files) {
        boolean java = false;
        boolean kotlin = false;
        boolean mapper = false;
        boolean xml = false;
        boolean yaml = false;
        boolean sql = false;
        boolean script = false;
        boolean vue = false;
        for (GitlabClient.DiffFile file : files) {
            String path = file.path() == null ? "" : file.path().toLowerCase(Locale.ROOT);
            if (path.endsWith(".java")) {
                java = true;
            }
            if (path.endsWith(".kt")) {
                kotlin = true;
            }
            if (path.endsWith(".xml") && path.contains("mapper")) {
                mapper = true;
            } else if (path.endsWith(".xml")) {
                xml = true;
            }
            if (path.endsWith(".yml") || path.endsWith(".yaml")) {
                yaml = true;
            }
            if (path.endsWith(".sql")) {
                sql = true;
            }
            if (path.endsWith(".ts") || path.endsWith(".js")) {
                script = true;
            }
            if (path.endsWith(".vue")) {
                vue = true;
            }
        }
        StringBuilder builder = new StringBuilder();
        if (java) {
            builder.append(read("path-rules/java.md")).append('\n');
        }
        if (kotlin) {
            builder.append(read("path-rules/kotlin.md")).append('\n');
        }
        if (mapper) {
            builder.append(read("path-rules/mapper.md")).append('\n');
        }
        if (xml) {
            builder.append(read("path-rules/xml.md")).append('\n');
        }
        if (yaml) {
            builder.append(read("path-rules/yaml.md")).append('\n');
        }
        if (sql) {
            builder.append(read("path-rules/sql.md")).append('\n');
        }
        if (script) {
            builder.append(read("path-rules/script.md")).append('\n');
        }
        if (vue) {
            builder.append(read("path-rules/vue.md")).append('\n');
        }
        return builder.toString().trim();
    }

    private static String read(String path) {
        try (InputStream input = PathRuleRegistry.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }
}
