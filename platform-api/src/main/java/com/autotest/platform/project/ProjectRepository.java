package com.autotest.platform.project;

import org.springframework.dao.DuplicateKeyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProjectRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ProjectRecord> findAll(boolean includeArchived) {
        String archivedClause = includeArchived ? "" : " WHERE archived_at IS NULL";
        return jdbc.query(
                "SELECT id, name, description, revision, archived_at, created_at, updated_at, target_allowlist_json "
                        + "FROM projects" + archivedClause + " ORDER BY updated_at DESC",
                (rs, rowNum) -> map(rs));
    }

    public ProjectRecord findById(UUID id) {
        List<ProjectRecord> projects = jdbc.query(
                "SELECT id, name, description, revision, archived_at, created_at, updated_at, target_allowlist_json "
                        + "FROM projects WHERE id = ?",
                (rs, rowNum) -> map(rs), id);
        return projects.isEmpty() ? null : projects.get(0);
    }

    public ProjectRecord findByIdForUpdate(UUID id) {
        List<ProjectRecord> projects = jdbc.query(
                "SELECT id, name, description, revision, archived_at, created_at, updated_at, target_allowlist_json "
                        + "FROM projects WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), id);
        return projects.isEmpty() ? null : projects.get(0);
    }

    public boolean existsActiveName(String name) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM projects WHERE archived_at IS NULL AND lower(name) = lower(?))",
                Boolean.class, name);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsActiveNameExcluding(String name, UUID excludedId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM projects "
                        + "WHERE archived_at IS NULL AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, name, excludedId);
        return Boolean.TRUE.equals(exists);
    }

    public ProjectRecord insert(String name, String description, UUID actorId) throws DuplicateKeyException {
        return insert(name, description, List.of(), actorId);
    }

    public ProjectRecord insert(String name, String description, List<String> targetAllowlist, UUID actorId)
            throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO projects (id, name, description, target_allowlist_json, revision, archived_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, 0, NULL, ?, ?, ?, ?)",
                id, name, description, writeAllowlist(targetAllowlist), actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(id);
    }

    public int updateDetails(UUID id, String name, String description, int expectedRevision, UUID actorId) {
        return updateDetails(id, name, description, List.of(), expectedRevision, actorId);
    }

    public int updateDetails(UUID id, String name, String description, List<String> targetAllowlist,
                             int expectedRevision, UUID actorId) {
        return jdbc.update(
                "UPDATE projects SET name = ?, description = ?, target_allowlist_json = ?::jsonb, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE id = ? AND revision = ?",
                name, description, writeAllowlist(targetAllowlist), actorId, Timestamp.from(Instant.now()), id,
                expectedRevision);
    }

    public int updateArchived(UUID id, boolean archived, int expectedRevision, UUID actorId) {
        return jdbc.update(
                "UPDATE projects SET archived_at = ?, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE id = ? AND revision = ?",
                archived ? Timestamp.from(Instant.now()) : null,
                actorId, Timestamp.from(Instant.now()), id, expectedRevision);
    }

    private ProjectRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("revision"),
                rs.getTimestamp("archived_at") != null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                readAllowlist(rs.getString("target_allowlist_json")));
    }

    private String writeAllowlist(List<String> targetAllowlist) {
        try {
            return objectMapper.writeValueAsString(targetAllowlist == null ? List.of() : targetAllowlist);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("目标白名单序列化失败", exception);
        }
    }

    private List<String> readAllowlist(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("目标白名单 JSON 无效", exception);
        }
    }
}
