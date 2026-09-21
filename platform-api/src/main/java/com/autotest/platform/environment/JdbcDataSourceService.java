package com.autotest.platform.environment;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.secret.SecretService;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JdbcDataSourceService {
    private final JdbcDataSourceRepository dataSources;
    private final EnvironmentRepository environments;
    private final ProjectRepository projects;
    private final SecretRepository secrets;
    private final SecretService secretService;

    public JdbcDataSourceService(JdbcDataSourceRepository dataSources, EnvironmentRepository environments,
                                 ProjectRepository projects, SecretRepository secrets, SecretService secretService) {
        this.dataSources = dataSources;
        this.environments = environments;
        this.projects = projects;
        this.secrets = secrets;
        this.secretService = secretService;
    }

    public List<JdbcDataSourceRecord> list(UUID projectId, UUID environmentId, boolean includeArchived) {
        requireEnvironment(projectId, environmentId);
        return dataSources.findAll(projectId, environmentId, includeArchived);
    }

    public JdbcDataSourceRecord get(UUID projectId, UUID id) {
        requireProject(projectId);
        JdbcDataSourceRecord result = dataSources.findById(projectId, id);
        if (result == null) throw notFound();
        return result;
    }

    @Transactional
    public JdbcDataSourceRecord create(UUID projectId, UUID environmentId, String name, String type, String host,
                                       Integer port, String databaseName, String username, String secretRef,
                                       JsonNode options, UUID actor) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        requireEnvironment(projectId, environmentId);
        Inputs input = normalize(projectId, name, type, host, port, databaseName, username, secretRef, options);
        if (dataSources.existsActiveName(projectId, environmentId, input.name)) throw conflict("NAME_CONFLICT", "数据源名称已存在");
        try {
            return dataSources.insert(projectId, environmentId, input.name, input.type, input.host, input.port,
                    input.databaseName, input.username, input.secretRef, input.options, actor);
        } catch (DuplicateKeyException e) {
            throw conflict("NAME_CONFLICT", "数据源名称已存在");
        }
    }

    @Transactional
    public JdbcDataSourceRecord update(UUID projectId, UUID id, UUID environmentId, String name, String type,
                                       String host, Integer port, String databaseName, String username,
                                       String secretRef, JsonNode options, Integer revision, UUID actor) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        JdbcDataSourceRecord current = requireForUpdate(projectId, id);
        checkRevision(current, revision);
        requireEnvironment(projectId, environmentId);
        Inputs input = normalize(projectId, name, type, host, port, databaseName, username, secretRef, options);
        if (dataSources.existsActiveNameExcluding(projectId, environmentId, input.name, id)) throw conflict("NAME_CONFLICT", "数据源名称已存在");
        if (dataSources.updateDetails(projectId, id, environmentId, input.name, input.type, input.host, input.port,
                input.databaseName, input.username, input.secretRef, input.options, revision, actor) != 1) {
            throw conflict("REVISION_CONFLICT", "数据源版本已变化");
        }
        return get(projectId, id);
    }

    @Transactional
    public JdbcDataSourceRecord archive(UUID projectId, UUID id, Integer revision, UUID actor) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        JdbcDataSourceRecord current = requireForUpdate(projectId, id);
        checkRevision(current, revision);
        if (dataSources.updateArchived(projectId, id, true, revision, actor) != 1) throw conflict("REVISION_CONFLICT", "数据源版本已变化");
        return get(projectId, id);
    }

    @Transactional
    public JdbcDataSourceRecord restore(UUID projectId, UUID id, Integer revision, UUID actor) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        JdbcDataSourceRecord current = requireForUpdate(projectId, id);
        checkRevision(current, revision);
        if (!current.archived()) throw conflict("REVISION_CONFLICT", "数据源版本已变化");
        if (dataSources.existsActiveNameExcluding(projectId, current.environmentId(), current.name(), id)) throw conflict("NAME_CONFLICT", "数据源名称已存在");
        if (dataSources.updateArchived(projectId, id, false, revision, actor) != 1) throw conflict("REVISION_CONFLICT", "数据源版本已变化");
        return get(projectId, id);
    }

    public ConnectionTestResult testConnection(UUID projectId, UUID id) {
        JdbcDataSourceRecord source = get(projectId, id);
        String password = secretService.resolveForRunner(projectId, source.secretRef());
        String url = jdbcUrl(source);
        try (Connection ignored = DriverManager.getConnection(url, source.username(), password)) {
            return new ConnectionTestResult(true, "连接成功");
        } catch (SQLException e) {
            return new ConnectionTestResult(false, "连接失败：" + safeSqlMessage(e));
        }
    }

    private Inputs normalize(UUID projectId, String name, String type, String host, Integer port, String databaseName,
                             String username, String secretRef, JsonNode options) {
        String n = name == null ? "" : name.strip();
        String t = type == null ? "" : type.strip().toUpperCase();
        String h = host == null ? "" : host.strip();
        String db = databaseName == null ? "" : databaseName.strip();
        String user = username == null ? "" : username.strip();
        String ref = secretRef == null ? "" : secretRef.strip();
        if (n.isBlank() || n.length() > 256 || t.isBlank() || h.isBlank() || h.contains(" ") || db.isBlank()
                || user.isBlank() || ref.isBlank() || port == null || port < 1 || port > 65535) {
            throw validation("JDBC 数据源参数不合法");
        }
        if (!t.equals("POSTGRESQL") && !t.equals("MYSQL")) throw validation("仅支持 PostgreSQL 或 MySQL");
        if (secrets.findActiveByName(projectId, ref) == null) throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "SECRET_REFERENCE_NOT_FOUND", "数据源密钥引用不存在");
        JsonNode opts = options == null || options.isNull() ? JsonNodeFactory.instance.objectNode() : options;
        if (!opts.isObject()) throw validation("JDBC 数据源 options 必须是对象");
        return new Inputs(n, t, h, port, db, user, ref, opts);
    }

    private String jdbcUrl(JdbcDataSourceRecord source) {
        String scheme = source.databaseType().equals("MYSQL") ? "jdbc:mysql" : "jdbc:postgresql";
        return scheme + "://" + source.host() + ":" + source.port() + "/" + source.databaseName();
    }

    private String safeSqlMessage(SQLException e) {
        String message = e.getMessage() == null ? "未知错误" : e.getMessage().replaceAll("(?i)(password|pwd)=\\S+", "$1=***");
        return message.length() > 256 ? message.substring(0, 256) : message;
    }

    private EnvironmentRecord requireEnvironment(UUID projectId, UUID environmentId) {
        requireProject(projectId);
        EnvironmentRecord result = environments.findById(projectId, environmentId);
        if (result == null) throw notFound();
        return result;
    }
    private ProjectRecord lockProject(UUID id) { ProjectRecord p = projects.findByIdForUpdate(id); if (p == null) throw notFound(); return p; }
    private void requireProject(UUID id) { if (projects.findById(id) == null) throw notFound(); }
    private JdbcDataSourceRecord requireForUpdate(UUID projectId, UUID id) { JdbcDataSourceRecord r = dataSources.findByIdForUpdate(projectId, id); if (r == null) throw notFound(); return r; }
    private static void writable(ProjectRecord p) { if (p.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档"); }
    private static void checkRevision(JdbcDataSourceRecord current, Integer revision) { if (revision == null || revision != current.revision()) throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "数据源版本已变化", Map.of("currentRevision", current.revision())); }
    private static ApiDomainException validation(String m) { return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", m); }
    private static ApiDomainException notFound() { return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在"); }
    private static ApiDomainException conflict(String c, String m) { return new ApiDomainException(HttpStatus.CONFLICT.value(), c, m); }
    private record Inputs(String name, String type, String host, int port, String databaseName, String username, String secretRef, JsonNode options) {}
    public record ConnectionTestResult(boolean success, String message) {}
}
