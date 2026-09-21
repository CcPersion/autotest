package com.autotest.platform.run;

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
public class RunRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public RunRecord findById(UUID projectId, UUID runId) {
        List<RunRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RunRecord findById(UUID runId) {
        List<RunRecord> rows = jdbc.query(select() + " WHERE id = ?",
                (rs, rowNum) -> map(rs), runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RunRecord findByIdempotencyKey(UUID projectId, String key) {
        List<RunRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> map(rs), projectId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RunRecord> findRecentByProjectId(UUID projectId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return jdbc.query(select() + " WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> map(rs), projectId, boundedLimit);
    }

    public RunRecord insert(UUID projectId, UUID environmentId, String targetType, UUID targetId,
                            UUID requestedBy, JsonNode executionPlan, String idempotencyKey,
                            String jmeterVersion) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO runs (id, project_id, environment_id, target_type, target_id, requested_by, status, "
                        + "execution_plan, idempotency_key, jmeter_version, created_at) VALUES (?, ?, ?, ?, ?, ?, "
                        + "'PENDING', ?::jsonb, ?, ?, ?)", id, projectId, environmentId, targetType, targetId,
                requestedBy, executionPlan.toString(), idempotencyKey, jmeterVersion, Timestamp.from(now));
        return findById(projectId, id);
    }

    public RunRecord cancel(UUID projectId, UUID runId) {
        jdbc.update("UPDATE runs SET status = 'CANCELED', finished_at = now() "
                        + "WHERE project_id = ? AND id = ? AND status = 'PENDING'", projectId, runId);
        jdbc.update("UPDATE runs SET cancel_requested = TRUE "
                        + "WHERE project_id = ? AND id = ? AND status = 'RUNNING'", projectId, runId);
        return findById(projectId, runId);
    }

    private String select() {
        return "SELECT id, project_id, environment_id, target_type, target_id, requested_by, status, "
                + "execution_plan, idempotency_key, jmeter_version, started_at, finished_at, exit_code, "
                + "jmx_path, jtl_path, log_path, cancel_requested, created_at FROM runs";
    }

    private RunRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new RunRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    rs.getObject("environment_id", UUID.class), rs.getString("target_type"),
                    rs.getObject("target_id", UUID.class), rs.getObject("requested_by", UUID.class),
                    rs.getString("status"), objectMapper.readTree(rs.getString("execution_plan")),
                    rs.getString("idempotency_key"), rs.getString("jmeter_version"), instant(rs, "started_at"),
                    instant(rs, "finished_at"), (Integer) rs.getObject("exit_code"), rs.getString("jmx_path"),
                    rs.getString("jtl_path"), rs.getString("log_path"), rs.getBoolean("cancel_requested"),
                    rs.getTimestamp("created_at").toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("stored execution plan JSON is invalid", exception);
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
