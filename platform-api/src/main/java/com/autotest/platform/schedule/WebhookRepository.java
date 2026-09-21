package com.autotest.platform.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class WebhookRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WebhookRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<WebhookRecord> findAll(UUID projectId) {
        return jdbc.query(select() + " WHERE project_id = ? ORDER BY name, id", (rs, rowNum) -> map(rs), projectId);
    }

    public WebhookRecord findById(UUID projectId, UUID id) {
        List<WebhookRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?", (rs, rowNum) -> map(rs),
                projectId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WebhookRecord findByIdForUpdate(UUID projectId, UUID id) {
        List<WebhookRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ? FOR UPDATE", (rs, rowNum) -> map(rs),
                projectId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WebhookRecord> findEnabled(UUID projectId, String event) {
        return jdbc.query(select() + " WHERE project_id = ? AND enabled = TRUE AND events_json @> ?::jsonb",
                (rs, rowNum) -> map(rs), projectId, jsonArray(List.of(event)));
    }

    public boolean existsName(UUID projectId, String name, UUID excludingId) {
        String sql = excludingId == null
                ? "SELECT EXISTS (SELECT 1 FROM webhook_configs WHERE project_id = ? AND lower(name) = lower(?))"
                : "SELECT EXISTS (SELECT 1 FROM webhook_configs WHERE project_id = ? AND lower(name) = lower(?) AND id <> ?)";
        Boolean exists = excludingId == null
                ? jdbc.queryForObject(sql, Boolean.class, projectId, name)
                : jdbc.queryForObject(sql, Boolean.class, projectId, name, excludingId);
        return Boolean.TRUE.equals(exists);
    }

    public WebhookRecord insert(UUID projectId, String name, String url, String secretRef, List<String> events,
                                boolean enabled, UUID actorId) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO webhook_configs (id, project_id, name, url, secret_ref, events_json, enabled, revision, "
                        + "created_by, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 0, ?, ?, ?, ?)",
                id, projectId, name, url, secretRef, jsonArray(events), enabled, actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public int update(UUID projectId, UUID id, String name, String url, String secretRef, List<String> events,
                      boolean enabled, int revision, UUID actorId) {
        return jdbc.update("UPDATE webhook_configs SET name = ?, url = ?, secret_ref = ?, events_json = ?::jsonb, "
                        + "enabled = ?, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND revision = ?", name, url, secretRef, jsonArray(events), enabled,
                actorId, Timestamp.from(Instant.now()), projectId, id, revision);
    }

    private String select() {
        return "SELECT id, project_id, name, url, secret_ref, events_json, enabled, revision, created_at, updated_at "
                + "FROM webhook_configs";
    }

    private WebhookRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new WebhookRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    rs.getString("name"), rs.getString("url"), rs.getString("secret_ref"),
                    json.readValue(rs.getString("events_json"), json.getTypeFactory().constructCollectionType(List.class, String.class)),
                    rs.getBoolean("enabled"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Webhook 事件配置无效", exception);
        }
    }

    private String jsonArray(List<String> events) {
        try {
            return json.writeValueAsString(events);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Webhook 事件配置无效", exception);
        }
    }
}
