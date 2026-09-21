package com.autotest.platform.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class TestSuiteRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TestSuiteRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<TestSuiteRecord> findAll(UUID projectId, boolean includeArchived) {
        String clause = includeArchived ? "" : " AND archived_at IS NULL";
        return jdbc.query(select() + " WHERE project_id = ?" + clause + " ORDER BY updated_at DESC, id",
                (rs, rowNum) -> map(rs), projectId).stream().map(this::withMembers).toList();
    }

    public TestSuiteRecord findById(UUID projectId, UUID suiteId) {
        List<TestSuiteRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, suiteId);
        return rows.isEmpty() ? null : withMembers(rows.get(0));
    }

    public TestSuiteRecord findActiveByIdForUpdate(UUID projectId, UUID suiteId) {
        List<TestSuiteRecord> rows = jdbc.query(select()
                        + " WHERE project_id = ? AND id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, suiteId);
        return rows.isEmpty() ? null : withMembers(rows.get(0));
    }

    public boolean existsActiveName(UUID projectId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM test_suites "
                + "WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?))",
                Boolean.class, projectId, name);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsActiveNameExcluding(UUID projectId, String name, UUID excludedId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM test_suites "
                + "WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, projectId, name, excludedId);
        return Boolean.TRUE.equals(exists);
    }

    public TestSuiteRecord insert(UUID projectId, String name, String description, UUID environmentId,
                                  List<TestSuiteWrite.MemberWrite> members, UUID actorId) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO test_suites (id, project_id, name, description, environment_id, revision, "
                        + "archived_at, created_by, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?)",
                id, projectId, name, description, environmentId, actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        replaceMembers(projectId, id, members);
        return findById(projectId, id);
    }

    public int updateDetails(UUID projectId, UUID suiteId, String name, String description, UUID environmentId,
                             List<TestSuiteWrite.MemberWrite> members, int expectedRevision, UUID actorId) {
        int updated = jdbc.update("UPDATE test_suites SET name = ?, description = ?, environment_id = ?, "
                        + "revision = revision + 1, updated_by = ?, updated_at = ? WHERE project_id = ? AND id = ? "
                        + "AND archived_at IS NULL AND revision = ?", name, description, environmentId, actorId,
                Timestamp.from(Instant.now()), projectId, suiteId, expectedRevision);
        if (updated == 1) {
            replaceMembers(projectId, suiteId, members);
        }
        return updated;
    }

    public int archive(UUID projectId, UUID suiteId, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE test_suites SET archived_at = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                Timestamp.from(Instant.now()), actorId, Timestamp.from(Instant.now()), projectId, suiteId,
                expectedRevision);
    }

    private void replaceMembers(UUID projectId, UUID suiteId, List<TestSuiteWrite.MemberWrite> members) {
        jdbc.update("DELETE FROM test_suite_members WHERE project_id = ? AND suite_id = ?", projectId, suiteId);
        for (TestSuiteWrite.MemberWrite member : members) {
            jdbc.update("INSERT INTO test_suite_members (id, suite_id, project_id, position, target_type, target_id, enabled) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", member.id(), suiteId, projectId, member.position(),
                    member.targetType(), member.targetId(), member.enabled());
        }
    }

    private TestSuiteRecord withMembers(TestSuiteRecord suite) {
        List<TestSuiteMemberRecord> members = jdbc.query("SELECT id, suite_id, project_id, position, target_type, target_id, enabled "
                        + "FROM test_suite_members WHERE project_id = ? AND suite_id = ? ORDER BY position, id",
                (rs, rowNum) -> new TestSuiteMemberRecord(rs.getObject("id", UUID.class),
                        rs.getObject("suite_id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getInt("position"), rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                        rs.getBoolean("enabled")), suite.projectId(), suite.id());
        return new TestSuiteRecord(suite.id(), suite.projectId(), suite.name(), suite.description(), suite.environmentId(),
                suite.revision(), suite.archived(), suite.createdAt(), suite.updatedAt(), members);
    }

    private String select() {
        return "SELECT id, project_id, name, description, environment_id, revision, archived_at, created_at, updated_at FROM test_suites";
    }

    private TestSuiteRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TestSuiteRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getObject("environment_id", UUID.class),
                rs.getInt("revision"), rs.getTimestamp("archived_at") != null,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), List.of());
    }
}
