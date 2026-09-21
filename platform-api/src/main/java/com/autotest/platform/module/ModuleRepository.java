package com.autotest.platform.module;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ModuleRepository {

    private final JdbcTemplate jdbc;

    public ModuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModuleRecord> findActiveByProject(UUID projectId) {
        return jdbc.query("SELECT id, project_id, parent_id, name, sort_order, revision, archived_at, "
                        + "created_at, updated_at FROM modules WHERE project_id = ? AND archived_at IS NULL "
                        + "ORDER BY sort_order, id",
                (rs, rowNum) -> map(rs), projectId);
    }

    public ModuleRecord findActiveById(UUID projectId, UUID moduleId) {
        List<ModuleRecord> rows = jdbc.query("SELECT id, project_id, parent_id, name, sort_order, revision, archived_at, "
                        + "created_at, updated_at FROM modules WHERE project_id = ? AND id = ? AND archived_at IS NULL",
                (rs, rowNum) -> map(rs), projectId, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ModuleRecord findActiveByIdForUpdate(UUID projectId, UUID moduleId) {
        List<ModuleRecord> rows = jdbc.query("SELECT id, project_id, parent_id, name, sort_order, revision, archived_at, "
                        + "created_at, updated_at FROM modules WHERE project_id = ? AND id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, moduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ModuleRecord> findActiveSiblings(UUID projectId, UUID parentId) {
        return jdbc.query("SELECT id, project_id, parent_id, name, sort_order, revision, archived_at, "
                        + "created_at, updated_at FROM modules WHERE project_id = ? AND archived_at IS NULL "
                        + "AND parent_id IS NOT DISTINCT FROM ? ORDER BY sort_order, id",
                (rs, rowNum) -> map(rs), projectId, parentId);
    }

    public boolean existsActiveSiblingName(UUID projectId, UUID parentId, String name, UUID excludedId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM modules WHERE project_id = ? AND archived_at IS NULL "
                + "AND parent_id IS NOT DISTINCT FROM ? AND lower(name) = lower(?)";
        if (excludedId == null) {
            sql += ")";
            return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, projectId, parentId, name));
        }
        sql += " AND id <> ? )";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, projectId, parentId, name, excludedId));
    }

    public ModuleRecord insert(UUID projectId, UUID parentId, String name, int sortOrder, UUID actorId)
            throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO modules (id, project_id, parent_id, name, sort_order, revision, archived_at, "
                        + "created_by, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?)",
                id, projectId, parentId, name, sortOrder, actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        return findActiveById(projectId, id);
    }

    public int updateName(UUID projectId, UUID moduleId, String name, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE modules SET name = ?, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                name, actorId, Timestamp.from(Instant.now()), projectId, moduleId, expectedRevision);
    }

    public int updateParentAndPosition(UUID projectId, UUID moduleId, UUID parentId, int sortOrder,
                                       int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE modules SET parent_id = ?, sort_order = ?, revision = revision + 1, "
                        + "updated_by = ?, updated_at = ? WHERE project_id = ? AND id = ? AND archived_at IS NULL "
                        + "AND revision = ?",
                parentId, sortOrder, actorId, Timestamp.from(Instant.now()), projectId, moduleId, expectedRevision);
    }

    public int updateSortOrder(UUID projectId, UUID moduleId, int sortOrder, UUID actorId) {
        return jdbc.update("UPDATE modules SET sort_order = ?, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND archived_at IS NULL",
                sortOrder, actorId, Timestamp.from(Instant.now()), projectId, moduleId);
    }

    public int archive(UUID projectId, UUID moduleId, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE modules SET archived_at = ?, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                Timestamp.from(Instant.now()), actorId, Timestamp.from(Instant.now()), projectId, moduleId,
                expectedRevision);
    }

    public int activeChildCount(UUID projectId, UUID parentId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM modules WHERE project_id = ? AND parent_id = ? "
                        + "AND archived_at IS NULL", Integer.class, projectId, parentId);
        return count == null ? 0 : count;
    }

    public int activeApiCount(UUID projectId, UUID moduleId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM api_definitions WHERE project_id = ? AND module_id = ? "
                        + "AND archived_at IS NULL", Integer.class, projectId, moduleId);
        return count == null ? 0 : count;
    }

    private static ModuleRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ModuleRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("name"),
                rs.getInt("sort_order"),
                rs.getInt("revision"),
                rs.getTimestamp("archived_at") != null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
