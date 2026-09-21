CREATE TABLE file_assets (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_file_assets_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_file_assets_kind CHECK (kind IN ('REQUEST_FILE', 'PKCS12')),
    CONSTRAINT ck_file_assets_status CHECK (status IN ('UPLOADING', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_file_assets_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_file_assets_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_file_assets_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_file_assets_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_file_assets_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_file_assets_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX ix_file_assets_project_status ON file_assets (project_id, status, updated_at DESC);
CREATE UNIQUE INDEX ux_file_assets_active_project_sha256 ON file_assets (project_id, sha256)
    WHERE status = 'ACTIVE';

CREATE TABLE file_asset_quota_reservations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    file_id UUID NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_file_asset_quota_reservation_file UNIQUE (file_id),
    CONSTRAINT fk_file_asset_quota_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_file_asset_quota_file FOREIGN KEY (project_id, file_id)
        REFERENCES file_assets (project_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_file_asset_quota_size CHECK (size_bytes > 0)
);

CREATE TABLE file_asset_orphan_objects (
    object_key VARCHAR(512) PRIMARY KEY,
    project_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_file_asset_orphan_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);
