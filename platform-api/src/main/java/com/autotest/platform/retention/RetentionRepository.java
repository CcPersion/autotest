package com.autotest.platform.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RetentionRepository {

    private final JdbcTemplate jdbc;

    public RetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RetentionRecord find(UUID projectId) {
        List<RetentionRecord> rows = jdbc.query("SELECT project_id, retention_days, revision, updated_at "
                        + "FROM project_retention_settings WHERE project_id = ?", (rs, rowNum) ->
                new RetentionRecord(rs.getObject("project_id", UUID.class), rs.getInt("retention_days"),
                        rs.getInt("revision"), rs.getTimestamp("updated_at").toInstant()), projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(UUID projectId, int retentionDays, int expectedRevision) {
        return jdbc.update("INSERT INTO project_retention_settings (project_id, retention_days, revision, updated_at) "
                        + "VALUES (?, ?, 0, ?) ON CONFLICT (project_id) DO UPDATE SET retention_days = EXCLUDED.retention_days, "
                        + "revision = project_retention_settings.revision + 1, updated_at = EXCLUDED.updated_at "
                        + "WHERE project_retention_settings.revision = ?", projectId, retentionDays,
                Timestamp.from(Instant.now()), expectedRevision);
    }

    public int deleteOldStepResults(UUID projectId, Instant cutoff) {
        return jdbc.update("DELETE FROM step_results WHERE run_id IN (SELECT id FROM runs WHERE project_id = ? "
                + "AND created_at < ? AND status IN ('PASSED','FAILED','CANCELED','INTERRUPTED','SKIPPED'))",
                projectId, Timestamp.from(cutoff));
    }

    public int deleteOldRuns(UUID projectId, Instant cutoff) {
        return jdbc.update("DELETE FROM runs WHERE project_id = ? AND created_at < ? "
                        + "AND status IN ('PASSED','FAILED','CANCELED','INTERRUPTED','SKIPPED')",
                projectId, Timestamp.from(cutoff));
    }
}
