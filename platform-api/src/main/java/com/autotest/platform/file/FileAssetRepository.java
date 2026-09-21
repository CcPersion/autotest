package com.autotest.platform.file;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class FileAssetRepository {
    private final JdbcTemplate jdbc;

    public FileAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    FileAssetRecord insertUploading(UUID projectId, String kind, String originalName, String mimeType,
                                    long size, String sha256, String objectKey, UUID actorId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO file_assets "
                        + "(id, project_id, kind, original_name, mime_type, size_bytes, sha256, status, object_key, "
                        + "revision, archived_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'UPLOADING', ?, 0, NULL, ?, ?, ?, ?)",
                id, projectId, kind, originalName, mimeType, size, sha256, objectKey,
                actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        return find(projectId, id);
    }

    int activate(UUID projectId, UUID id) {
        return jdbc.update("UPDATE file_assets SET status = 'ACTIVE', updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND status = 'UPLOADING'",
                Timestamp.from(Instant.now()), projectId, id);
    }

    public FileAssetRecord find(UUID projectId, UUID id) {
        List<FileAssetRecord> records = jdbc.query("SELECT id, project_id, kind, original_name, mime_type, "
                        + "size_bytes, sha256, status, object_key, revision, created_at, updated_at "
                        + "FROM file_assets WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, id);
        return records.isEmpty() ? null : records.get(0);
    }

    List<FileAssetRecord> findAll(UUID projectId, String status) {
        String sql = "SELECT id, project_id, kind, original_name, mime_type, size_bytes, sha256, status, object_key, "
                + "revision, created_at, updated_at FROM file_assets WHERE project_id = ?";
        if (status != null) {
            sql += " AND status = ?";
        }
        sql += " ORDER BY updated_at DESC";
        return status == null
                ? jdbc.query(sql, (rs, rowNum) -> map(rs), projectId)
                : jdbc.query(sql, (rs, rowNum) -> map(rs), projectId, status);
    }

    int archive(UUID projectId, UUID id, int revision) {
        return jdbc.update("UPDATE file_assets SET status = 'ARCHIVED', archived_at = ?, revision = revision + 1, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND status = 'ACTIVE' AND revision = ?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), projectId, id, revision);
    }

    long reservedBytes(UUID projectId) {
        Long total = jdbc.queryForObject("SELECT "
                + "(SELECT COALESCE(SUM(size_bytes), 0) FROM file_assets WHERE project_id = ? AND status = 'ACTIVE') "
                + "+ (SELECT COALESCE(SUM(size_bytes), 0) FROM file_asset_quota_reservations WHERE project_id = ?)",
                Long.class, projectId, projectId);
        return total == null ? 0L : total;
    }

    void insertReservation(UUID projectId, UUID fileId, long size) {
        jdbc.update("INSERT INTO file_asset_quota_reservations (id, project_id, file_id, size_bytes, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), projectId, fileId, size,
                Timestamp.from(Instant.now()));
    }

    void deleteReservation(UUID fileId) {
        jdbc.update("DELETE FROM file_asset_quota_reservations WHERE file_id = ?", fileId);
    }

    boolean isInUse(UUID projectId, UUID fileId) {
        String reference = "%" + fileId + "%";
        Boolean used = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM runs "
                + "WHERE project_id = ? AND status IN ('PENDING', 'RUNNING') AND execution_plan::text LIKE ?) "
                + "OR EXISTS (SELECT 1 FROM api_definitions WHERE project_id = ? AND archived_at IS NULL AND request_spec::text LIKE ?) "
                + "OR EXISTS (SELECT 1 FROM api_cases WHERE project_id = ? AND archived_at IS NULL "
                + "AND (case_spec::text LIKE ? OR variables_json::text LIKE ? OR assertions_json::text LIKE ?)) "
                + "OR EXISTS (SELECT 1 FROM environments WHERE project_id = ? AND archived_at IS NULL "
                + "AND request_options_json::text LIKE ?)", Boolean.class,
                projectId, reference, projectId, reference, projectId, reference, reference, reference,
                projectId, reference);
        return Boolean.TRUE.equals(used);
    }

    private FileAssetRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FileAssetRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("kind"), rs.getString("original_name"), rs.getString("mime_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("status"),
                rs.getString("object_key"), rs.getInt("revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
