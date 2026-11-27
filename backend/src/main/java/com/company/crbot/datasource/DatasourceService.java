package com.company.crbot.datasource;

import com.company.crbot.common.ApiException;
import com.company.crbot.common.CurrentUser;
import com.company.crbot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

@Service
public class DatasourceService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceService.class);

    private final DatasourceConfigRepository repository;
    private final AppProperties properties;

    public DatasourceService(DatasourceConfigRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<View> list() {
        return repository.findAllByOrderByIdDesc().stream().map(this::toView).toList();
    }

    @Transactional
    public View create(SaveRequest request) {
        requireEncKey();
        DatasourceConfigEntity entity = new DatasourceConfigEntity();
        apply(entity, request, true);
        // 测通后才落库，失败不留下半成品数据源
        checkConnection(entity.getDbType(), entity.getJdbcUrl(), entity.getUsername(), request.password());
        entity.setReadonlyChecked(true);
        log.info("创建数据源 user={} name={}", CurrentUser.username(), entity.getName());
        return toView(repository.save(entity));
    }

    @Transactional
    public View update(Long id, SaveRequest request) {
        requireEncKey();
        DatasourceConfigEntity entity = require(id);
        apply(entity, request, false);
        String password = request.password() == null || request.password().isBlank()
                ? AesCipher.decrypt(entity.getPasswordEnc(), properties.getEncKey())
                : request.password();
        // 编辑后必须重新测通，通过才写回
        checkConnection(entity.getDbType(), entity.getJdbcUrl(), entity.getUsername(), password);
        entity.setReadonlyChecked(true);
        log.info("更新数据源 user={} id={}", CurrentUser.username(), id);
        return toView(repository.save(entity));
    }

    public void probe(ProbeRequest request) {
        requireEncKey();
        String dbType = requireDbType(request.dbType());
        // 用主机、端口、库名拼出连接，不接收手写 JDBC 地址
        String jdbcUrl = buildJdbcUrl(dbType, request.host(), request.port(), request.databaseName());
        if (request.username() == null || request.username().isBlank()) {
            throw new ApiException("请填写用户名");
        }
        String password = request.password();
        // 编辑时密码留空，用已保存的密码测通
        if ((password == null || password.isBlank()) && request.id() != null) {
            password = AesCipher.decrypt(require(request.id()).getPasswordEnc(), properties.getEncKey());
        }
        if (password == null || password.isBlank()) {
            throw new ApiException("密码不能为空");
        }
        checkConnection(dbType, jdbcUrl, request.username().trim(), password);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public View test(Long id) {
        DatasourceConfigEntity entity = require(id);
        String password = AesCipher.decrypt(entity.getPasswordEnc(), properties.getEncKey());
        try (Connection connection = DriverManager.getConnection(entity.getJdbcUrl(), entity.getUsername(), password);
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(8);
            // 先确认网络和账号能连上
            try (ResultSet ping = statement.executeQuery("SELECT 1")) {
                ping.next();
            }
            assertReadonly(entity.getDbType(), statement);
        } catch (ApiException ex) {
            entity.setReadonlyChecked(false);
            repository.save(entity);
            throw ex;
        } catch (Exception ex) {
            entity.setReadonlyChecked(false);
            repository.save(entity);
            throw new ApiException("数据源连通失败");
        }
        entity.setReadonlyChecked(true);
        log.info("数据源只读校验通过 user={} id={}", CurrentUser.username(), id);
        return toView(repository.save(entity));
    }

    private void checkConnection(String dbType, String jdbcUrl, String username, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(8);
            // 先确认网络和账号能连上
            try (ResultSet ping = statement.executeQuery("SELECT 1")) {
                ping.next();
            }
            assertReadonly(dbType, statement);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("数据源连通失败");
        }
    }

    @Transactional
    public void delete(Long id) {
        log.info("删除数据源 user={} id={}", CurrentUser.username(), id);
        repository.delete(require(id));
    }

    private void assertReadonly(String dbType, Statement statement) throws Exception {
        if ("postgres".equals(dbType)) {
            boolean superuser = false;
            try (ResultSet roles = statement.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
                if (roles.next()) {
                    superuser = roles.getBoolean(1);
                }
            }
            if (superuser) {
                throw new ApiException("数据库账号是超级用户，不能作为只读数据源");
            }
            try (ResultSet grants = statement.executeQuery("""
                    SELECT privilege_type FROM information_schema.role_table_grants
                    WHERE grantee = current_user
                      AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
                    """)) {
                if (grants.next()) {
                    throw new ApiException("数据库账号仍有写权限: " + grants.getString(1));
                }
            }
            try (ResultSet owned = statement.executeQuery("""
                    SELECT count(*) FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind = 'r'
                      AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                    """)) {
                if (owned.next() && owned.getLong(1) > 0) {
                    throw new ApiException("数据库账号拥有业务表，不能作为只读数据源");
                }
            }
            return;
        }
        if ("dm".equals(dbType)) {
            // 达梦的 SYSDBA 以及带写权限、DBA 角色的账号不能当只读源
            assertDamengReadonly(statement);
            return;
        }
        try (ResultSet grants = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER")) {
            while (grants.next()) {
                String grant = grants.getString(1).toUpperCase(Locale.ROOT);
                if (grant.contains("ALL PRIVILEGES") || grant.contains("INSERT") || grant.contains("UPDATE")
                        || grant.contains("DELETE") || grant.contains("DROP") || grant.contains("ALTER")) {
                    throw new ApiException("数据库账号不是只读: " + grants.getString(1));
                }
            }
        }
    }

    private void apply(DatasourceConfigEntity entity, SaveRequest request, boolean creating) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException("数据源名称不能为空");
        }
        String dbType = requireDbType(request.dbType());
        // 保存时同样只认主机、端口、库名
        String jdbcUrl = buildJdbcUrl(dbType, request.host(), request.port(), request.databaseName());
        if (request.username() == null || request.username().isBlank()) {
            throw new ApiException("用户名不能为空");
        }
        entity.setName(request.name().trim());
        entity.setDbType(dbType);
        entity.setJdbcUrl(jdbcUrl);
        entity.setUsername(request.username().trim());
        entity.setEnabled(request.enabled() == null || request.enabled());
        if (request.password() != null && !request.password().isBlank()) {
            entity.setPasswordEnc(AesCipher.encrypt(request.password(), properties.getEncKey()));
        } else if (creating) {
            throw new ApiException("密码不能为空");
        }
    }

    private DatasourceConfigEntity require(Long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "数据源不存在"));
    }

    private void requireEncKey() {
        if (properties.getEncKey() == null || properties.getEncKey().isBlank()) {
            throw new ApiException("未配置 CRBOT_ENC_KEY");
        }
    }

    private String requireDbType(String raw) {
        String dbType = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!dbType.equals("postgres") && !dbType.equals("mysql") && !dbType.equals("dm")) {
            throw new ApiException("数据库类型只允许 postgres、mysql 或 dm");
        }
        return dbType;
    }

    private void assertDamengReadonly(Statement statement) throws Exception {
        try (ResultSet user = statement.executeQuery("SELECT USER FROM DUAL")) {
            if (user.next()) {
                String name = user.getString(1);
                if (name != null && (name.equalsIgnoreCase("SYSDBA") || name.equalsIgnoreCase("SYSSSO") || name.equalsIgnoreCase("SYSAUDITOR"))) {
                    throw new ApiException("达梦的管理账号不能作为只读数据源");
                }
            }
        }
        try (ResultSet roles = statement.executeQuery("SELECT GRANTED_ROLE FROM USER_ROLE_PRIVS")) {
            while (roles.next()) {
                String role = roles.getString(1);
                if (role != null && (role.equalsIgnoreCase("DBA") || role.equalsIgnoreCase("RESOURCE"))) {
                    throw new ApiException("数据库账号拥有可写角色: " + role);
                }
            }
        }
        try (ResultSet privileges = statement.executeQuery("SELECT PRIVILEGE FROM USER_SYS_PRIVS")) {
            while (privileges.next()) {
                String privilege = privileges.getString(1);
                if (isDamengWritePrivilege(privilege)) {
                    throw new ApiException("数据库账号不是只读: " + privilege);
                }
            }
        }
        try (ResultSet tablePrivileges = statement.executeQuery("""
                SELECT PRIVILEGE FROM USER_TAB_PRIVS
                WHERE PRIVILEGE IN ('INSERT', 'UPDATE', 'DELETE', 'ALTER', 'DROP', 'TRUNCATE')
                """)) {
            if (tablePrivileges.next()) {
                throw new ApiException("数据库账号不是只读: " + tablePrivileges.getString(1));
            }
        }
    }

    private boolean isDamengWritePrivilege(String privilege) {
        if (privilege == null || privilege.isBlank()) {
            return false;
        }
        String upper = privilege.toUpperCase(Locale.ROOT).trim();
        if (upper.equals("DBA")) {
            return true;
        }
        // PUBLIC 默认的 INSERT TABLE 只能改自己的对象；带 ANY 才能改其他模式
        if (!upper.contains(" ANY ")) {
            return false;
        }
        return !upper.startsWith("SELECT ");
    }

    private String buildJdbcUrl(String dbType, String host, Integer port, String databaseName) {
        if (host == null || host.isBlank()) {
            throw new ApiException("主机不能为空");
        }
        String checkedHost = host.trim();
        if (!checkedHost.matches("[A-Za-z0-9._-]+")) {
            // 拒绝把整段 jdbc 地址填进主机
            throw new ApiException("主机只能包含字母、数字、点、下划线和中横线");
        }
        if (port == null || port < 1 || port > 65535) {
            throw new ApiException("端口必须是 1 到 65535");
        }
        if (databaseName == null || databaseName.isBlank()) {
            throw new ApiException("库名不能为空");
        }
        String database = databaseName.trim();
        if (!database.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException("库名只能包含字母、数字、下划线和中横线");
        }
        return jdbcPrefix(dbType) + checkedHost + ":" + port + "/" + database;
    }

    private String jdbcPrefix(String dbType) {
        if ("postgres".equals(dbType)) {
            return "jdbc:postgresql://";
        }
        if ("mysql".equals(dbType)) {
            return "jdbc:mysql://";
        }
        // 达梦库名放在路径上，对应模式名
        return "jdbc:dm://";
    }

    private Endpoint splitJdbc(String dbType, String jdbcUrl) {
        String prefix = jdbcPrefix(dbType);
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            throw new ApiException("已保存的连接无法拆成主机和端口");
        }
        String rest = jdbcUrl.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new ApiException("已保存的连接无法拆成主机和端口");
        }
        String hostPort = rest.substring(0, slash);
        String database = rest.substring(slash + 1);
        int query = database.indexOf('?');
        if (query >= 0) {
            // 展示时丢掉查询参数，编辑只回填主机、端口、库名
            database = database.substring(0, query);
        }
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0 || colon == hostPort.length() - 1) {
            throw new ApiException("已保存的连接无法拆成主机和端口");
        }
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException ex) {
            throw new ApiException("已保存的连接无法拆成主机和端口");
        }
        return new Endpoint(hostPort.substring(0, colon), port, database);
    }

    private View toView(DatasourceConfigEntity entity) {
        Endpoint endpoint = splitJdbc(entity.getDbType(), entity.getJdbcUrl());
        return new View(entity.getId(), entity.getName(), entity.getDbType(), endpoint.host(), endpoint.port(),
                endpoint.databaseName(), entity.getUsername(), entity.isReadonlyChecked(), entity.isEnabled(), entity.getCreatedAt());
    }

    public record SaveRequest(String name, String dbType, String host, Integer port, String databaseName,
                              String username, String password, Boolean enabled) {
    }

    public record ProbeRequest(Long id, String dbType, String host, Integer port, String databaseName,
                               String username, String password) {
    }

    public record View(Long id, String name, String dbType, String host, Integer port, String databaseName,
                       String username, boolean readonlyChecked, boolean enabled, java.time.OffsetDateTime createdAt) {
    }

    private record Endpoint(String host, int port, String databaseName) {
    }
}
