package com.autotest.platform.file;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
class FileAssetOrphanRepository {
    private final JdbcTemplate jdbc;

    FileAssetOrphanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(UUID projectId, String objectKey) {
        jdbc.update("INSERT INTO file_asset_orphan_objects (object_key, project_id, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (object_key) DO NOTHING",
                objectKey, projectId, Timestamp.from(Instant.now().plusSeconds(24 * 3600)),
                Timestamp.from(Instant.now()));
    }
}
