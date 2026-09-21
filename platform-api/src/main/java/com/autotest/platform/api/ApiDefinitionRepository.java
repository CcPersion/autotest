package com.autotest.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ApiDefinitionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApiDefinitionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ApiDefinitionRecord> findAll(UUID projectId, UUID moduleId, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("SELECT id, project_id, module_id, name, http_method, url_template, "
                + "request_spec, revision, archived_at, created_at, updated_at FROM api_definitions "
                + "WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (moduleId != null) {
            sql.append(" AND module_id = ?");
            args.add(moduleId);
        }
        if (!includeArchived) {
            sql.append(" AND archived_at IS NULL");
        }
        sql.append(" ORDER BY updated_at DESC, id");
        return jdbc.query(sql.toString(), (rs, rowNum) -> map(rs), args.toArray());
    }

    public ApiDefinitionRecord findById(UUID projectId, UUID definitionId) {
        List<ApiDefinitionRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, definitionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ApiDefinitionRecord findActiveByIdForUpdate(UUID projectId, UUID definitionId) {
        List<ApiDefinitionRecord> rows = jdbc.query(select()
                        + " WHERE project_id = ? AND id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, definitionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsActiveName(UUID projectId, UUID moduleId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM api_definitions "
                        + "WHERE project_id = ? AND module_id IS NOT DISTINCT FROM ? AND archived_at IS NULL "
                        + "AND lower(name) = lower(?))", Boolean.class, projectId, moduleId, name);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsActiveNameExcluding(UUID projectId, UUID moduleId, String name, UUID excludedId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM api_definitions "
                        + "WHERE project_id = ? AND module_id IS NOT DISTINCT FROM ? AND archived_at IS NULL "
                        + "AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, projectId, moduleId, name, excludedId);
        return Boolean.TRUE.equals(exists);
    }

    public ApiDefinitionRecord insert(UUID projectId, UUID moduleId, String name, String method,
                                      String urlTemplate, JsonNode requestSpec, UUID actorId)
            throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO api_definitions (id, project_id, module_id, name, http_method, url_template, "
                        + "request_spec, revision, archived_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 0, NULL, ?, ?, ?, ?)",
                id, projectId, moduleId, name, method, urlTemplate, requestSpec.toString(), actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public int updateDetails(UUID projectId, UUID definitionId, UUID moduleId, String name, String method,
                             String urlTemplate, JsonNode requestSpec, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE api_definitions SET module_id = ?, name = ?, http_method = ?, url_template = ?, "
                        + "request_spec = ?::jsonb, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                moduleId, name, method, urlTemplate, requestSpec.toString(), actorId, Timestamp.from(Instant.now()),
                projectId, definitionId, expectedRevision);
    }

    public int archive(UUID projectId, UUID definitionId, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE api_definitions SET archived_at = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                Timestamp.from(Instant.now()), actorId, Timestamp.from(Instant.now()), projectId, definitionId,
                expectedRevision);
    }

    private String select() {
        return "SELECT id, project_id, module_id, name, http_method, url_template, request_spec, revision, "
                + "archived_at, created_at, updated_at FROM api_definitions";
    }

    private ApiDefinitionRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ApiDefinitionRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("module_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("http_method"),
                    rs.getString("url_template"),
                    objectMapper.readTree(rs.getString("request_spec")),
                    rs.getInt("revision"),
                    rs.getTimestamp("archived_at") != null,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("stored API definition JSON is invalid", exception);
        }
    }
}
