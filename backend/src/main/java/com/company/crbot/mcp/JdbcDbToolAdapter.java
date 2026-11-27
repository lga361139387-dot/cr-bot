package com.company.crbot.mcp;

import com.company.crbot.common.ApiException;
import com.company.crbot.datasource.AesCipher;
import com.company.crbot.datasource.DatasourceConfigEntity;
import com.company.crbot.datasource.DatasourceConfigRepository;
import com.company.crbot.config.AppProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Component
public class JdbcDbToolAdapter implements DbToolPort {

    private final DatasourceConfigRepository repository;
    private final AppProperties properties;

    public JdbcDbToolAdapter(DatasourceConfigRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public List<String> listTables(String schema) {
        DatasourceConfigEntity datasource = requireDatasource();
        String checkedSchema = blank(schema);
        if (checkedSchema != null) {
            SqlFirewall.requireTable(checkedSchema);
        }
        try (Connection connection = open(datasource); Statement statement = statement(connection)) {
            String sql = tableListSql(datasource.getDbType());
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(sql)) {
                prepared.setString(1, checkedSchema);
                prepared.setString(2, checkedSchema);
                prepared.setQueryTimeout(8);
                try (ResultSet rows = prepared.executeQuery()) {
                    List<String> tables = new ArrayList<>();
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                    return tables;
                }
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("读取表清单失败");
        }
    }

    @Override
    public String describeTable(String table) {
        DatasourceConfigEntity datasource = requireDatasource();
        String[] parts = split(SqlFirewall.requireTable(table));
        try (Connection connection = open(datasource)) {
            String sql = describeSql(datasource.getDbType());
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(sql)) {
                prepared.setString(1, parts[1]);
                prepared.setString(2, parts[0]);
                prepared.setString(3, parts[0]);
                prepared.setQueryTimeout(8);
                return readRows(prepared.executeQuery());
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("读取表结构失败: " + parts[1]);
        }
    }

    @Override
    public String listIndexes(String table) {
        DatasourceConfigEntity datasource = requireDatasource();
        String[] parts = split(SqlFirewall.requireTable(table));
        try (Connection connection = open(datasource)) {
            String sql = indexSql(datasource.getDbType());
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(sql)) {
                prepared.setString(1, parts[1]);
                prepared.setString(2, parts[0]);
                prepared.setString(3, parts[0]);
                prepared.setQueryTimeout(8);
                return readRows(prepared.executeQuery());
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("读取索引失败: " + parts[1]);
        }
    }

    @Override
    public String explainSql(String sql) {
        DatasourceConfigEntity datasource = requireDatasource();
        // 只执行通过防火墙的 EXPLAIN
        String explain = SqlFirewall.requireExplain(sql);
        try (Connection connection = open(datasource); Statement statement = statement(connection)) {
            try (ResultSet rows = statement.executeQuery(explain)) {
                return readRows(rows);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("EXPLAIN 执行失败");
        }
    }

    private String tableListSql(String dbType) {
        if ("postgres".equals(dbType)) {
            return """
                    SELECT table_schema || '.' || table_name
                    FROM information_schema.tables
                    WHERE table_type = 'BASE TABLE'
                      AND table_schema NOT IN ('pg_catalog', 'information_schema')
                      AND (? IS NULL OR table_schema = ?)
                    ORDER BY 1
                    LIMIT 200
                    """;
        }
        if ("dm".equals(dbType)) {
            // 达梦用 ALL_TABLES，模式名对应页面上的库名
            return """
                    SELECT FULL_NAME FROM (
                        SELECT OWNER || '.' || TABLE_NAME AS FULL_NAME
                        FROM ALL_TABLES
                        WHERE OWNER NOT IN ('SYS', 'SYSSSO', 'SYSAUDITOR', 'CTISYS')
                          AND (? IS NULL OR OWNER = ?)
                        ORDER BY 1
                    ) WHERE ROWNUM <= 200
                    """;
        }
        return """
                SELECT CONCAT(table_schema, '.', table_name)
                FROM information_schema.tables
                WHERE table_type = 'BASE TABLE'
                  AND table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                  AND (? IS NULL OR table_schema = ?)
                ORDER BY 1
                LIMIT 200
                """;
    }

    private String describeSql(String dbType) {
        if ("postgres".equals(dbType)) {
            return """
                    SELECT table_schema, column_name, data_type, is_nullable,
                           col_description((quote_ident(table_schema) || '.' || quote_ident(table_name))::regclass, ordinal_position)
                    FROM information_schema.columns
                    WHERE table_name = ? AND (? IS NULL OR table_schema = ?)
                    ORDER BY table_schema, ordinal_position
                    """;
        }
        if ("dm".equals(dbType)) {
            // 不限定页面上填的库名，同一个实例里的其他模式也要能看到
            return """
                    SELECT OWNER, COLUMN_NAME, DATA_TYPE, NULLABLE, DATA_LENGTH
                    FROM ALL_TAB_COLUMNS
                    WHERE TABLE_NAME = ? AND (? IS NULL OR OWNER = ?)
                    ORDER BY OWNER, COLUMN_ID
                    """;
        }
        return """
                SELECT table_schema, column_name, column_type, is_nullable, column_comment
                FROM information_schema.columns
                WHERE table_name = ? AND (? IS NULL OR table_schema = ?)
                ORDER BY table_schema, ordinal_position
                """;
    }

    private String indexSql(String dbType) {
        if ("postgres".equals(dbType)) {
            return """
                    SELECT schemaname, indexname, indexdef
                    FROM pg_indexes
                    WHERE tablename = ? AND (? IS NULL OR schemaname = ?)
                    """;
        }
        if ("dm".equals(dbType)) {
            return """
                    SELECT i.OWNER, i.INDEX_NAME, i.UNIQUENESS, c.COLUMN_NAME
                    FROM ALL_INDEXES i
                    JOIN ALL_IND_COLUMNS c
                      ON i.OWNER = c.INDEX_OWNER AND i.INDEX_NAME = c.INDEX_NAME AND i.TABLE_NAME = c.TABLE_NAME
                    WHERE i.TABLE_NAME = ? AND (? IS NULL OR i.OWNER = ?)
                    ORDER BY i.OWNER, i.INDEX_NAME, c.COLUMN_POSITION
                    """;
        }
        return """
                SELECT table_schema, index_name, non_unique, column_name, index_type
                FROM information_schema.statistics
                WHERE table_name = ? AND (? IS NULL OR table_schema = ?)
                ORDER BY table_schema, index_name, seq_in_index
                """;
    }

    public boolean hasReadyDatasource() {
        // 恰好一个已启用且测通的只读源，评审才允许查库
        return readyDatasources().size() == 1;
    }

    private DatasourceConfigEntity requireDatasource() {
        List<DatasourceConfigEntity> enabled = readyDatasources();
        if (enabled.isEmpty()) {
            throw new ApiException("未配置启用且通过只读校验的数据源");
        }
        if (enabled.size() > 1) {
            throw new ApiException("存在多个启用的只读数据源，请只保留一个");
        }
        return enabled.get(0);
    }

    private List<DatasourceConfigEntity> readyDatasources() {
        return repository.findByEnabledTrue().stream()
                .filter(DatasourceConfigEntity::isReadonlyChecked)
                .toList();
    }

    private Connection open(DatasourceConfigEntity datasource) throws Exception {
        String password = AesCipher.decrypt(datasource.getPasswordEnc(), properties.getEncKey());
        Connection connection = DriverManager.getConnection(datasource.getJdbcUrl(), datasource.getUsername(), password);
        connection.setReadOnly(true);
        return connection;
    }

    private Statement statement(Connection connection) throws Exception {
        Statement statement = connection.createStatement();
        statement.setQueryTimeout(8);
        return statement;
    }

    private String[] split(String table) {
        String[] parts = table.split("\\.");
        if (parts.length == 1) {
            return new String[]{null, parts[0]};
        }
        return new String[]{parts[0], parts[1]};
    }

    private String blank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String readRows(ResultSet rows) throws Exception {
        StringBuilder builder = new StringBuilder();
        int columns = rows.getMetaData().getColumnCount();
        int count = 0;
        while (rows.next()) {
            if (count > 0) {
                builder.append('\n');
            }
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    builder.append(" | ");
                }
                builder.append(rows.getString(i));
            }
            count++;
            if (count >= 200) {
                break;
            }
        }
        if (count == 0) {
            return "无结果";
        }
        return builder.toString();
    }
}
