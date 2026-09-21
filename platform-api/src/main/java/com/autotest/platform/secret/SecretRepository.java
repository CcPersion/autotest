package com.autotest.platform.secret;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class SecretRepository {

    private final JdbcTemplate jdbc;

    public SecretRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SecretRecord> findAll(UUID projectId, boolean includeArchived) {
        String archivedClause = includeArchived ? "" : " AND archived_at IS NULL";
        return jdbc.query("SELECT id, project_id, name, revision, archived_at, created_at, updated_at "
                        + "FROM secrets WHERE project_id = ?" + archivedClause + " ORDER BY name, id",
                (rs, rowNum) -> map(rs), projectId);
    }

    public SecretRecord findActiveByName(UUID projectId, String name) {
        List<SecretRecord> rows = jdbc.query("SELECT id, project_id, name, revision, archived_at, created_at, updated_at "
                        + "FROM secrets WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?)",
                (rs, rowNum) -> map(rs), projectId, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EncryptedSecret findActiveMaterialByName(UUID projectId, String name) {
        List<EncryptedSecret> rows = jdbc.query("SELECT id, project_id, name, ciphertext, nonce "
                        + "FROM secrets WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?)",
                (rs, rowNum) -> new EncryptedSecret(rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getString("name"),
                        rs.getBytes("ciphertext"), rs.getBytes("nonce")), projectId, name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SecretRecord findById(UUID projectId, UUID secretId) {
        List<SecretRecord> rows = jdbc.query("SELECT id, project_id, name, revision, archived_at, created_at, updated_at "
                        + "FROM secrets WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, secretId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SecretRecord findActiveByIdForUpdate(UUID projectId, UUID secretId) {
        List<SecretRecord> rows = jdbc.query("SELECT id, project_id, name, revision, archived_at, created_at, updated_at "
                        + "FROM secrets WHERE project_id = ? AND id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, secretId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsActiveName(UUID projectId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM secrets "
                        + "WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?))",
                Boolean.class, projectId, name);
        return Boolean.TRUE.equals(exists);
    }

    public SecretRecord insert(UUID projectId, UUID secretId, String name, byte[] ciphertext, byte[] nonce, UUID actorId)
            throws DuplicateKeyException {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO secrets (id, project_id, name, ciphertext, nonce, revision, archived_at, "
                        + "created_by, updated_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?)",
                secretId, projectId, name, ciphertext, nonce, actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, secretId);
    }

    public int updateCiphertext(UUID projectId, UUID secretId, byte[] ciphertext, byte[] nonce,
                                int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE secrets SET ciphertext = ?, nonce = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                ciphertext, nonce, actorId, Timestamp.from(Instant.now()), projectId, secretId, expectedRevision);
    }

    public int archive(UUID projectId, UUID secretId, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE secrets SET archived_at = ?, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                Timestamp.from(Instant.now()), actorId, Timestamp.from(Instant.now()), projectId, secretId,
                expectedRevision);
    }

    private static SecretRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SecretRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("name"),
                rs.getInt("revision"),
                rs.getTimestamp("archived_at") != null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public record EncryptedSecret(UUID id, UUID projectId, String name, byte[] ciphertext, byte[] nonce) {
    }
}
