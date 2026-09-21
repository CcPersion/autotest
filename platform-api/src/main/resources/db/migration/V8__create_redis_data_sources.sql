CREATE TABLE redis_data_sources (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    host VARCHAR(512) NOT NULL,
    port INTEGER NOT NULL,
    database_number INTEGER NOT NULL DEFAULT 0,
    username VARCHAR(256),
    secret_ref VARCHAR(256),
    options_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_redis_data_sources_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_redis_data_sources_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_redis_data_sources_database CHECK (database_number BETWEEN 0 AND 15),
    CONSTRAINT ck_redis_data_sources_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_redis_data_sources_host_nonempty CHECK (btrim(host) <> ''),
    CONSTRAINT ck_redis_data_sources_options_object CHECK (jsonb_typeof(options_json) = 'object'),
    CONSTRAINT ck_redis_data_sources_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_redis_data_sources_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_redis_data_sources_environment FOREIGN KEY (project_id, environment_id)
        REFERENCES environments (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_redis_data_sources_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_redis_data_sources_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_redis_data_sources_active_project_environment_name
    ON redis_data_sources (project_id, environment_id, lower(name))
    WHERE archived_at IS NULL;

CREATE INDEX ix_redis_data_sources_environment ON redis_data_sources (project_id, environment_id, archived_at);
