package com.autotest.platform.api;

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
public class ApiCaseRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApiCaseRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ApiCaseRecord> findAll(UUID projectId, UUID definitionId, boolean includeArchived) {
        String archivedClause = includeArchived ? "" : " AND archived_at IS NULL";
        return jdbc.query(select() + " WHERE project_id = ? AND api_definition_id = ?" + archivedClause
                        + " ORDER BY updated_at DESC, id", (rs, rowNum) -> map(rs), projectId, definitionId);
    }

    public ApiCaseRecord findById(UUID projectId, UUID definitionId, UUID caseId) {
        List<ApiCaseRecord> rows = jdbc.query(select()
                        + " WHERE project_id = ? AND api_definition_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, definitionId, caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ApiCaseRecord findActiveByProjectId(UUID projectId, UUID caseId) {
        List<ApiCaseRecord> rows = jdbc.query(select()
                        + " WHERE project_id = ? AND id = ? AND archived_at IS NULL",
                (rs, rowNum) -> map(rs), projectId, caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ApiCaseRecord> findActiveByProjectId(UUID projectId) {
        return jdbc.query(select() + " WHERE project_id = ? AND archived_at IS NULL ORDER BY updated_at DESC, id",
                (rs, rowNum) -> map(rs), projectId);
    }

    public ApiCaseRecord findActiveByIdForUpdate(UUID projectId, UUID definitionId, UUID caseId) {
        List<ApiCaseRecord> rows = jdbc.query(select()
                        + " WHERE project_id = ? AND api_definition_id = ? AND id = ? "
                        + "AND archived_at IS NULL FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, definitionId, caseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsActiveName(UUID projectId, UUID definitionId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM api_cases "
                        + "WHERE project_id = ? AND api_definition_id = ? AND archived_at IS NULL "
                        + "AND lower(name) = lower(?))", Boolean.class, projectId, definitionId, name);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsActiveNameExcluding(UUID projectId, UUID definitionId, String name, UUID excludedId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM api_cases "
                        + "WHERE project_id = ? AND api_definition_id = ? AND archived_at IS NULL "
                        + "AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, projectId, definitionId, name, excludedId);
        return Boolean.TRUE.equals(exists);
    }

    public ApiCaseRecord insert(UUID projectId, UUID definitionId, String name, JsonNode caseSpec,
                                JsonNode variables, JsonNode assertions, UUID actorId)
            throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO api_cases (id, project_id, api_definition_id, name, case_spec, variables_json, "
                        + "assertions_json, revision, archived_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, 0, NULL, ?, ?, ?, ?)",
                id, projectId, definitionId, name, caseSpec.toString(), variables.toString(), assertions.toString(),
                actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, definitionId, id);
    }

    public int updateDetails(UUID projectId, UUID definitionId, UUID caseId, String name, JsonNode caseSpec,
                             JsonNode variables, JsonNode assertions, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE api_cases SET name = ?, case_spec = ?::jsonb, variables_json = ?::jsonb, "
                        + "assertions_json = ?::jsonb, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND api_definition_id = ? AND id = ? AND archived_at IS NULL "
                        + "AND revision = ?",
                name, caseSpec.toString(), variables.toString(), assertions.toString(), actorId,
                Timestamp.from(Instant.now()), projectId, definitionId, caseId, expectedRevision);
    }

    public int archive(UUID projectId, UUID definitionId, UUID caseId, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE api_cases SET archived_at = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND api_definition_id = ? AND id = ? "
                        + "AND archived_at IS NULL AND revision = ?",
                Timestamp.from(Instant.now()), actorId, Timestamp.from(Instant.now()), projectId, definitionId,
                caseId, expectedRevision);
    }

    private String select() {
        return "SELECT id, project_id, api_definition_id, name, case_spec, variables_json, assertions_json, "
                + "revision, archived_at, created_at, updated_at FROM api_cases";
    }

    private ApiCaseRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ApiCaseRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("api_definition_id", UUID.class),
                    rs.getString("name"),
                    objectMapper.readTree(rs.getString("case_spec")),
                    objectMapper.readTree(rs.getString("variables_json")),
                    objectMapper.readTree(rs.getString("assertions_json")),
                    rs.getInt("revision"),
                    rs.getTimestamp("archived_at") != null,
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("stored API case JSON is invalid", exception);
        }
    }
}
