CREATE TABLE ai_model_configs (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    api_key_secret_ref VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_ai_model_configs_name UNIQUE (name),
    CONSTRAINT ck_ai_model_configs_provider CHECK (provider_type IN ('OPENAI_COMPATIBLE', 'FAKE')),
    CONSTRAINT ck_ai_model_configs_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_ai_model_configs_base_url_nonempty CHECK (btrim(base_url) <> ''),
    CONSTRAINT ck_ai_model_configs_model_nonempty CHECK (btrim(model_name) <> ''),
    CONSTRAINT ck_ai_model_configs_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_ai_model_configs_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_model_configs_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE TABLE ai_sessions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    model_config_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_ai_sessions_title_nonempty CHECK (btrim(title) <> ''),
    CONSTRAINT fk_ai_sessions_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_sessions_model FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_sessions_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE TABLE ai_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    tool_name VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_ai_messages_role CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    CONSTRAINT ck_ai_messages_event_type CHECK (event_type IN ('TEXT', 'TOOL_CALL', 'TOOL_RESULT', 'ERROR', 'DONE')),
    CONSTRAINT fk_ai_messages_session FOREIGN KEY (session_id) REFERENCES ai_sessions (id) ON DELETE CASCADE
);

CREATE INDEX ix_ai_sessions_project_updated ON ai_sessions (project_id, updated_at DESC);
CREATE INDEX ix_ai_messages_session_created ON ai_messages (session_id, created_at, id);
