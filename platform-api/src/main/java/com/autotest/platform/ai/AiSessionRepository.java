package com.autotest.platform.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AiSessionRepository {
    private final JdbcTemplate jdbc;

    public AiSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AiSessionRecord insert(UUID projectId, UUID modelConfigId, String title, UUID actorId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_sessions (id, project_id, model_config_id, title, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, projectId, modelConfigId, title, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public List<AiSessionRecord> findAll(UUID projectId) {
        return jdbc.query(select() + " WHERE project_id = ? ORDER BY updated_at DESC, id",
                (rs, rowNum) -> map(rs), projectId);
    }

    public AiSessionRecord findById(UUID projectId, UUID id) {
        List<AiSessionRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AiMessageRecord appendMessage(UUID sessionId, String role, String eventType,
                                         String content, String toolName) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_messages (id, session_id, role, event_type, content, tool_name, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, sessionId, role, eventType, content,
                toolName, Timestamp.from(now));
        jdbc.update("UPDATE ai_sessions SET updated_at = ? WHERE id = ?", Timestamp.from(now), sessionId);
        return new AiMessageRecord(id, sessionId, role, eventType, content, toolName, now);
    }

    public List<AiMessageRecord> findMessages(UUID sessionId) {
        return jdbc.query("SELECT id, session_id, role, event_type, content, tool_name, created_at FROM ai_messages "
                        + "WHERE session_id = ? ORDER BY created_at, id", (rs, rowNum) -> new AiMessageRecord(
                rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getString("role"),
                rs.getString("event_type"), rs.getString("content"), rs.getString("tool_name"),
                rs.getTimestamp("created_at").toInstant()), sessionId);
    }

    private static String select() {
        return "SELECT id, project_id, model_config_id, title, created_by, created_at, updated_at FROM ai_sessions";
    }

    private static AiSessionRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AiSessionRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("model_config_id", UUID.class), rs.getString("title"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
