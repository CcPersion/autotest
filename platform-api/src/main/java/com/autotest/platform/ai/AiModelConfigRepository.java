package com.autotest.platform.ai;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AiModelConfigRepository {
    private final JdbcTemplate jdbc;

    public AiModelConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AiModelConfigRecord> findAll() {
        return jdbc.query(select() + " ORDER BY name, id", (rs, rowNum) -> map(rs));
    }

    public AiModelConfigRecord findById(UUID id) {
        List<AiModelConfigRecord> rows = jdbc.query(select() + " WHERE id = ?", (rs, rowNum) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsName(String name) {
        Boolean result = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ai_model_configs WHERE lower(name) = lower(?))",
                Boolean.class, name);
        return Boolean.TRUE.equals(result);
    }

    public boolean existsNameExcluding(String name, UUID id) {
        Boolean result = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ai_model_configs WHERE lower(name) = lower(?) AND id <> ?)",
                Boolean.class, name, id);
        return Boolean.TRUE.equals(result);
    }

    public AiModelConfigRecord insert(String name, String providerType, String baseUrl, String modelName,
                                      String secretRef, boolean enabled, UUID actorId) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_model_configs (id, name, provider_type, base_url, model_name, api_key_secret_ref, "
                        + "enabled, revision, created_by, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)",
                id, name, providerType, baseUrl, modelName, secretRef, enabled, actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(id);
    }

    public int update(UUID id, String name, String providerType, String baseUrl, String modelName,
                      String secretRef, boolean enabled, int revision, UUID actorId) {
        return jdbc.update("UPDATE ai_model_configs SET name = ?, provider_type = ?, base_url = ?, model_name = ?, "
                        + "api_key_secret_ref = ?, enabled = ?, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE id = ? AND revision = ?", name, providerType, baseUrl, modelName, secretRef, enabled,
                actorId, Timestamp.from(Instant.now()), id, revision);
    }

    private static String select() {
        return "SELECT id, name, provider_type, base_url, model_name, api_key_secret_ref, enabled, revision, "
                + "created_at, updated_at FROM ai_model_configs";
    }

    private static AiModelConfigRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AiModelConfigRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("base_url"), rs.getString("model_name"), rs.getString("api_key_secret_ref"),
                rs.getBoolean("enabled"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("provider_type"));
    }
}
