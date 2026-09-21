CREATE TABLE secrets (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(128) NOT NULL,
    ciphertext BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_secrets_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_secrets_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_secrets_ciphertext_nonempty CHECK (octet_length(ciphertext) > 0),
    CONSTRAINT ck_secrets_nonce_length CHECK (octet_length(nonce) = 12),
    CONSTRAINT ck_secrets_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_secrets_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_secrets_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_secrets_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_secrets_active_project_name
    ON secrets (project_id, lower(name))
    WHERE archived_at IS NULL;
