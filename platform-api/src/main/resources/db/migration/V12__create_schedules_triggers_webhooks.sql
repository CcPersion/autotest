CREATE TABLE schedules (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    suite_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    cron_expression VARCHAR(128) NOT NULL,
    zone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 0,
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_schedules_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_schedules_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_schedules_cron_nonempty CHECK (btrim(cron_expression) <> ''),
    CONSTRAINT ck_schedules_zone_nonempty CHECK (btrim(zone_id) <> ''),
    CONSTRAINT ck_schedules_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_schedules_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedules_suite_same_project FOREIGN KEY (project_id, suite_id)
        REFERENCES test_suites (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedules_environment_same_project FOREIGN KEY (project_id, environment_id)
        REFERENCES environments (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedules_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedules_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_schedules_active_project_name
    ON schedules (project_id, lower(name));
CREATE INDEX ix_schedules_due ON schedules (enabled, next_run_at);

CREATE TABLE trigger_tokens (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(128) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT uq_trigger_tokens_project_id_id UNIQUE (project_id, id),
    CONSTRAINT uq_trigger_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_trigger_tokens_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_trigger_tokens_hash_nonempty CHECK (btrim(token_hash) <> ''),
    CONSTRAINT fk_trigger_tokens_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trigger_tokens_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_trigger_tokens_active_project_name
    ON trigger_tokens (project_id, lower(name)) WHERE active = TRUE;

CREATE TABLE webhook_configs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(128) NOT NULL,
    url TEXT NOT NULL,
    secret_ref VARCHAR(128) NOT NULL,
    events_json JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_webhook_configs_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_webhook_configs_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_webhook_configs_url_nonempty CHECK (btrim(url) <> ''),
    CONSTRAINT ck_webhook_configs_secret_ref_nonempty CHECK (btrim(secret_ref) <> ''),
    CONSTRAINT ck_webhook_configs_events_array CHECK (jsonb_typeof(events_json) = 'array'),
    CONSTRAINT ck_webhook_configs_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_webhook_configs_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_webhook_configs_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_webhook_configs_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX ux_webhook_configs_active_project_name
    ON webhook_configs (project_id, lower(name));
