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
public class EnvironmentRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EnvironmentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<EnvironmentRecord> findAll(UUID projectId, boolean includeArchived) {
        String archivedClause = includeArchived ? "" : " AND archived_at IS NULL";
        return jdbc.query("SELECT id, project_id, name, base_url, variables_json, request_options_json, revision, archived_at, "
                        + "created_at, updated_at FROM environments WHERE project_id = ?" + archivedClause
                        + " ORDER BY name, id", (rs, rowNum) -> map(rs), projectId);
    }

    public EnvironmentRecord findById(UUID projectId, UUID environmentId) {
        List<EnvironmentRecord> rows = jdbc.query("SELECT id, project_id, name, base_url, variables_json, request_options_json, revision, "
                        + "archived_at, created_at, updated_at FROM environments WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, environmentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EnvironmentRecord findByIdForUpdate(UUID projectId, UUID environmentId) {
        List<EnvironmentRecord> rows = jdbc.query("SELECT id, project_id, name, base_url, variables_json, request_options_json, revision, "
                        + "archived_at, created_at, updated_at FROM environments WHERE project_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, environmentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsActiveName(UUID projectId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM environments "
                        + "WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?))",
                Boolean.class, projectId, name);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsActiveNameExcluding(UUID projectId, String name, UUID excludedId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM environments WHERE project_id = ? "
                        + "AND archived_at IS NULL AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, projectId, name, excludedId);
        return Boolean.TRUE.equals(exists);
    }

    public EnvironmentRecord insert(UUID projectId, String name, String baseUrl, JsonNode variables,
                                    JsonNode requestOptions, UUID actorId)
            throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO environments (id, project_id, name, base_url, variables_json, request_options_json, revision, archived_at, "
                        + "created_by, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, 0, NULL, ?, ?, ?, ?)",
                id, projectId, name, baseUrl, variables.toString(), requestOptions.toString(), actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public int updateDetails(UUID projectId, UUID environmentId, String name, String baseUrl, JsonNode variables,
                             JsonNode requestOptions, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE environments SET name = ?, base_url = ?, variables_json = ?::jsonb, request_options_json = ?::jsonb, "
                        + "revision = revision + 1, updated_by = ?, updated_at = ? WHERE project_id = ? AND id = ? "
                        + "AND revision = ?",
                name, baseUrl, variables.toString(), requestOptions.toString(), actorId, Timestamp.from(Instant.now()), projectId,
                environmentId, expectedRevision);
    }

    public int updateArchived(UUID projectId, UUID environmentId, boolean archived, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE environments SET archived_at = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND revision = ?",
                archived ? Timestamp.from(Instant.now()) : null, actorId, Timestamp.from(Instant.now()), projectId,
                environmentId, expectedRevision);
    }

    private EnvironmentRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new EnvironmentRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("base_url"),
                    objectMapper.readTree(rs.getString("variables_json")),
                    objectMapper.readTree(rs.getString("request_options_json")),
                    rs.getInt("revision"),
                    rs.getTimestamp("archived_at") != null,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("stored environment JSON is invalid");
        }
    }
}
