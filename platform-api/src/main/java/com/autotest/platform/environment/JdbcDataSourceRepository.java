package com.autotest.platform.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDataSourceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcDataSourceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<JdbcDataSourceRecord> findAll(UUID projectId, UUID environmentId, boolean includeArchived) {
        String archived = includeArchived ? "" : " AND archived_at IS NULL";
        return jdbc.query(select() + " WHERE project_id = ? AND environment_id = ?" + archived
                        + " ORDER BY name, id", (rs, n) -> map(rs), projectId, environmentId);
    }

    public JdbcDataSourceRecord findById(UUID projectId, UUID id) {
        List<JdbcDataSourceRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, n) -> map(rs), projectId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public JdbcDataSourceRecord findByIdForUpdate(UUID projectId, UUID id) {
        List<JdbcDataSourceRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ? FOR UPDATE",
                (rs, n) -> map(rs), projectId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsActiveName(UUID projectId, UUID environmentId, String name) {
        Boolean value = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM jdbc_data_sources WHERE project_id = ? "
                + "AND environment_id = ? AND archived_at IS NULL AND lower(name) = lower(?))", Boolean.class,
                projectId, environmentId, name);
        return Boolean.TRUE.equals(value);
    }

    public boolean existsActiveNameExcluding(UUID projectId, UUID environmentId, String name, UUID id) {
        Boolean value = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM jdbc_data_sources WHERE project_id = ? "
                + "AND environment_id = ? AND archived_at IS NULL AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, projectId, environmentId, name, id);
        return Boolean.TRUE.equals(value);
    }

    public JdbcDataSourceRecord insert(UUID projectId, UUID environmentId, String name, String type, String host,
                                       int port, String databaseName, String username, String secretRef,
                                       JsonNode options, UUID actor) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO jdbc_data_sources (id, project_id, environment_id, name, database_type, host, port, "
                        + "database_name, username, secret_ref, options_json, revision, archived_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 0, NULL, ?, ?, ?, ?)", id, projectId,
                environmentId, name, type, host, port, databaseName, username, secretRef, options.toString(), actor,
                actor, Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public int updateDetails(UUID projectId, UUID id, UUID environmentId, String name, String type, String host,
                             int port, String databaseName, String username, String secretRef, JsonNode options,
                             int expectedRevision, UUID actor) {
        return jdbc.update("UPDATE jdbc_data_sources SET environment_id = ?, name = ?, database_type = ?, host = ?, "
                        + "port = ?, database_name = ?, username = ?, secret_ref = ?, options_json = ?::jsonb, "
                        + "revision = revision + 1, updated_by = ?, updated_at = ? WHERE project_id = ? AND id = ? "
                        + "AND archived_at IS NULL AND revision = ?", environmentId, name, type, host, port,
                databaseName, username, secretRef, options.toString(), actor, Timestamp.from(Instant.now()), projectId,
                id, expectedRevision);
    }

    public int updateArchived(UUID projectId, UUID id, boolean archived, int expectedRevision, UUID actor) {
        return jdbc.update("UPDATE jdbc_data_sources SET archived_at = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND revision = ?",
                archived ? Timestamp.from(Instant.now()) : null, actor, Timestamp.from(Instant.now()), projectId, id,
                expectedRevision);
    }

    private String select() {
        return "SELECT id, project_id, environment_id, name, database_type, host, port, database_name, username, "
                + "secret_ref, options_json, revision, archived_at, created_at, updated_at FROM jdbc_data_sources";
    }

    private JdbcDataSourceRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new JdbcDataSourceRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    rs.getObject("environment_id", UUID.class), rs.getString("name"), rs.getString("database_type"),
                    rs.getString("host"), rs.getInt("port"), rs.getString("database_name"), rs.getString("username"),
                    rs.getString("secret_ref"), json.readTree(rs.getString("options_json")), rs.getInt("revision"),
                    rs.getTimestamp("archived_at") != null, rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (IOException e) {
            throw new IllegalStateException("stored JDBC data source JSON is invalid", e);
        }
    }
}
