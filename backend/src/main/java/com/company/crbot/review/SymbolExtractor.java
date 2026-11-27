package com.company.crbot.review;

import com.company.crbot.gitlab.GitlabClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SymbolExtractor {

    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile("\\b(?:public|private|protected)\\s+[\\w<>,.?\\s\\[\\]]+\\s+(\\w+)\\s*\\(");
    private static final Pattern TABLE = Pattern.compile("@Table(?:Name)?\\s*\\(\\s*(?:name\\s*=\\s*)?\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern TABLE_FIELD = Pattern.compile("@TableField\\s*\\(\\s*(?:value\\s*=\\s*)?\"([A-Za-z_][A-Za-z0-9_]*)\"");
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FIELD = Pattern.compile("^\\s*(?:private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>,.?\\[\\]\\s]+\\s+\\w+\\s*(?:=|;)");
    private static final Pattern DDL_TABLE = Pattern.compile("(?i)\\b(?:alter|create)\\s+table\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?[`\"]?([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COMPLEX_SQL = Pattern.compile("(?i)(\\bjoin\\b|\\bgroup\\s+by\\b|\\bunion\\b|\\bexists\\b|\\(\\s*select\\b|like\\s+['\"]%)");
    private static final Pattern SQLISH = Pattern.compile("(?i)\\b(select|update|delete|from|where)\\b");
    private static final Set<String> SQL_WORDS = Set.of(
            "select", "where", "set", "on", "and", "or", "left", "right", "inner", "outer", "join", "from", "values", "dual", "table");

    private SymbolExtractor() {
    }

    public static String symbols(List<GitlabClient.DiffFile> files) {
        Set<String> found = new LinkedHashSet<>();
        for (GitlabClient.DiffFile file : files) {
            collect(found, TYPE, file.patch());
            collect(found, METHOD, file.patch());
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String symbol : found) {
            if (count >= 80) {
                break;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(symbol);
            count++;
        }
        return builder.toString();
    }

    public static Set<String> tableNames(List<GitlabClient.DiffFile> files) {
        Set<String> tables = new LinkedHashSet<>();
        for (GitlabClient.DiffFile file : files) {
            collect(tables, TABLE, file.patch());
            collect(tables, SQL_TABLE, file.patch());
            if (tables.size() >= 8) {
                break;
            }
        }
        return tables;
    }

    public static boolean touchesPersistence(List<GitlabClient.DiffFile> files) {
        // 只有字段/DDL 或复杂 SQL 才需要查库，实体上的普通注解改动不算
        return dbChange(files).needsLookup();
    }

    public static DbChange dbChange(List<GitlabClient.DiffFile> files) {
        Set<String> schemaTables = new LinkedHashSet<>();
        Set<String> sqlTables = new LinkedHashSet<>();
        Set<String> entityPaths = new LinkedHashSet<>();
        Set<String> removedColumns = new LinkedHashSet<>();
        Set<String> addedColumns = new LinkedHashSet<>();
        List<String> complexSql = new ArrayList<>();
        boolean schemaTouched = false;
        if (files == null) {
            return new DbChange(false, Set.of(), Set.of(), List.of(), Set.of(), Set.of(), Set.of());
        }
        for (GitlabClient.DiffFile file : files) {
            String path = file.path() == null ? "" : file.path();
            String patch = file.patch() == null ? "" : file.patch();
            // 只看真正增删的行，diff 上下文里的 @TableName 不能当成变更
            List<String> changed = changedLines(patch, false);
            List<String> added = changedLines(patch, true);
            if (hasSchemaChange(path, changed)) {
                schemaTouched = true;
                // 字段变更所在的实体文件，表名可能不在这个 diff 块里
                entityPaths.add(path);
                // 表名从该文件 diff 的注解或 DDL 读取
                collect(schemaTables, TABLE, patch);
                for (String line : changed) {
                    collect(schemaTables, DDL_TABLE, line);
                }
                collectMappedColumns(patch, removedColumns, addedColumns);
            }
            String addedText = String.join("\n", added);
            if (isComplexSql(path, addedText) && complexSql.size() < 3) {
                // 复杂 SQL 才拿去对索引和 EXPLAIN
                complexSql.add(addedText.length() > 1500 ? addedText.substring(0, 1500) : addedText);
                collect(sqlTables, SQL_TABLE, addedText);
            }
            if (schemaTables.size() >= 8 && complexSql.size() >= 3) {
                break;
            }
        }
        // from/join 后面如果扫到关键字，不当成表名
        sqlTables.removeIf(name -> SQL_WORDS.contains(name.toLowerCase(Locale.ROOT)));
        return new DbChange(schemaTouched, Set.copyOf(schemaTables), Set.copyOf(sqlTables), List.copyOf(complexSql),
                Set.copyOf(entityPaths), Set.copyOf(removedColumns), Set.copyOf(addedColumns));
    }

    public static String tableNameIn(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher matcher = TABLE.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        // 实体类上的 @TableName 才是要去库里核对的表
        return matcher.group(1);
    }

    public record DbChange(boolean schemaTouched, Set<String> schemaTables, Set<String> sqlTables, List<String> complexSql,
                           Set<String> entityPaths, Set<String> removedColumns, Set<String> addedColumns) {
        public boolean needsLookup() {
            // 字段变更或复杂 SQL 才查库
            return schemaTouched || !complexSql.isEmpty();
        }
    }

    public static String query(String title, List<GitlabClient.DiffFile> files, String description) {
        StringBuilder builder = new StringBuilder();
        if (title != null && !title.isBlank()) {
            builder.append(title).append('\n');
        }
        for (GitlabClient.DiffFile file : files) {
            builder.append(file.path()).append('\n');
        }
        builder.append(symbols(files)).append('\n');
        if (description != null && !description.isBlank()) {
            builder.append(description.length() > 200 ? description.substring(0, 200) : description);
        }
        return builder.toString();
    }

    private static boolean entityFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        // 只把实体类文件上的字段增删当成表结构变更
        return lower.contains("/entity/") || lower.contains("\\entity\\")
                || lower.endsWith("do.java") || lower.endsWith("entity.java") || lower.endsWith("po.java");
    }

    private static boolean hasSchemaChange(String path, List<String> changed) {
        boolean entity = entityFile(path);
        boolean sqlFile = path.toLowerCase(Locale.ROOT).endsWith(".sql");
        for (String line : changed) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (entity && (FIELD.matcher(trimmed).find() || trimmed.contains("@TableField") || trimmed.contains("@Column("))) {
                // 实体字段、字段注解变更需要对照库里的列
                return true;
            }
            if (sqlFile && (DDL_TABLE.matcher(trimmed).find() || trimmed.toLowerCase(Locale.ROOT).contains("column"))) {
                return true;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (DDL_TABLE.matcher(trimmed).find() || lower.contains("add column") || lower.contains("drop column")
                    || lower.contains("modify column") || lower.contains("change column")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComplexSql(String path, String added) {
        if (added.isBlank() || !COMPLEX_SQL.matcher(added).find()) {
            // 没有 join、子查询、group by 这类写法，不查索引
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        boolean sqlFile = lower.endsWith(".sql") || lower.endsWith(".xml") || lower.contains("/mapper/");
        return sqlFile || SQLISH.matcher(added).find();
    }

    private static List<String> changedLines(String patch, boolean addedOnly) {
        List<String> lines = new ArrayList<>();
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (addedOnly) {
                if (line.startsWith("+")) {
                    lines.add(line.substring(1));
                }
                continue;
            }
            if (line.startsWith("+") || line.startsWith("-")) {
                lines.add(line.substring(1));
            }
        }
        return lines;
    }

    private static void collectMappedColumns(String patch, Set<String> removedColumns, Set<String> addedColumns) {
        for (String raw : patch.split("\\R")) {
            if (raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("@@")) {
                continue;
            }
            boolean removed = raw.startsWith("-");
            boolean added = raw.startsWith("+");
            if (!removed && !added) {
                continue;
            }
            Matcher matcher = TABLE_FIELD.matcher(raw.substring(1));
            if (!matcher.find()) {
                continue;
            }
            // 减号行是从实体删掉的列，加号行是新增映射
            if (removed) {
                removedColumns.add(matcher.group(1));
            } else {
                addedColumns.add(matcher.group(1));
            }
        }
    }

    private static void collect(Set<String> found, Pattern pattern, String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
    }
}
