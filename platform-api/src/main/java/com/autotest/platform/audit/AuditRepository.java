package com.autotest.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public AuditEventRecord insert(AuditEventWrite write) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO audit_events (id, actor_id, project_id, action, resource_type, resource_id, "
                        + "trace_id, run_id, step_id, metadata_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                id, write.actorId(), write.projectId(), write.action(), write.resourceType(), write.resourceId(),
                write.traceId(), write.runId(), write.stepId(), write.metadata().toString(), Timestamp.from(now));
        return findById(id);
    }

    public List<AuditEventRecord> find(UUID projectId, String action, String traceId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        StringBuilder sql = new StringBuilder(select()).append(" WHERE project_id = ?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action);
        }
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(traceId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(boundedLimit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> map(rs), args.toArray());
    }

    private AuditEventRecord findById(UUID id) {
        List<AuditEventRecord> rows = jdbc.query(select() + " WHERE id = ?", (rs, rowNum) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String select() {
        return "SELECT id, actor_id, project_id, action, resource_type, resource_id, trace_id, run_id, step_id, "
                + "metadata_json, created_at FROM audit_events";
    }

    private AuditEventRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            JsonNode metadata = json.readTree(rs.getString("metadata_json"));
            Timestamp created = rs.getTimestamp("created_at");
            return new AuditEventRecord(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                    rs.getObject("project_id", UUID.class), rs.getString("action"), rs.getString("resource_type"),
                    rs.getObject("resource_id", UUID.class), rs.getString("trace_id"), rs.getObject("run_id", UUID.class),
                    rs.getObject("step_id", UUID.class), metadata, created.toInstant());
        } catch (Exception exception) {
            throw new IllegalStateException("审计事件 JSON 无效", exception);
        }
    }
}
