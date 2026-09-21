package com.autotest.platform.schedule;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class TriggerTokenRepository {
    private final JdbcTemplate jdbc;

    public TriggerTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TriggerTokenRecord> findAll(UUID projectId) {
        return jdbc.query("SELECT id, project_id, name, token_hash, active, created_by, created_at, last_used_at "
                        + "FROM trigger_tokens WHERE project_id = ? ORDER BY name, id", (rs, rowNum) -> map(rs), projectId);
    }

    public TriggerTokenRecord findById(UUID projectId, UUID tokenId) {
        List<TriggerTokenRecord> rows = jdbc.query("SELECT id, project_id, name, token_hash, active, created_by, created_at, last_used_at "
                        + "FROM trigger_tokens WHERE project_id = ? AND id = ?", (rs, rowNum) -> map(rs), projectId, tokenId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TriggerTokenRecord findByHash(UUID projectId, String tokenHash) {
        List<TriggerTokenRecord> rows = jdbc.query("SELECT id, project_id, name, token_hash, active, created_by, created_at, last_used_at "
                        + "FROM trigger_tokens WHERE project_id = ? AND token_hash = ? AND active = TRUE",
                (rs, rowNum) -> map(rs), projectId, tokenHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsName(UUID projectId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM trigger_tokens WHERE project_id = ? "
                        + "AND lower(name) = lower(?) AND active = TRUE)", Boolean.class, projectId, name);
        return Boolean.TRUE.equals(exists);
    }

    public TriggerTokenRecord insert(UUID projectId, String name, String hash, UUID actorId) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO trigger_tokens (id, project_id, name, token_hash, active, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, TRUE, ?, ?)", id, projectId, name, hash, actorId, Timestamp.from(now));
        return findById(projectId, id);
    }

    public int revoke(UUID projectId, UUID tokenId) {
        return jdbc.update("UPDATE trigger_tokens SET active = FALSE WHERE project_id = ? AND id = ? AND active = TRUE",
                projectId, tokenId);
    }

    public int touch(UUID projectId, UUID tokenId) {
        return jdbc.update("UPDATE trigger_tokens SET last_used_at = now() WHERE project_id = ? AND id = ? AND active = TRUE",
                projectId, tokenId);
    }

    private static TriggerTokenRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        return new TriggerTokenRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("name"), rs.getString("token_hash"), rs.getBoolean("active"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                lastUsed == null ? null : lastUsed.toInstant());
    }
}
