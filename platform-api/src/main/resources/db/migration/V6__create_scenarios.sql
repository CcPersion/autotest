CREATE TABLE scenarios (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    variables_json JSONB NOT NULL,
    settings_json JSONB NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_scenarios_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_scenarios_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_scenarios_variables_object CHECK (jsonb_typeof(variables_json) = 'object'),
    CONSTRAINT ck_scenarios_settings_object CHECK (jsonb_typeof(settings_json) = 'object'),
    CONSTRAINT ck_scenarios_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_scenarios_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_scenarios_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_scenarios_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_scenarios_active_project_name
    ON scenarios (project_id, lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE scenario_steps (
    id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL,
    project_id UUID NOT NULL,
    parent_id UUID,
    position INTEGER NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    section VARCHAR(16) NOT NULL DEFAULT 'MAIN',
    reference_mode VARCHAR(16),
    api_case_id UUID,
    failure_strategy VARCHAR(16) NOT NULL DEFAULT 'STOP',
    step_config JSONB NOT NULL,
    CONSTRAINT uq_scenario_steps_scenario_id_id UNIQUE (scenario_id, id),
    CONSTRAINT ck_scenario_steps_position_nonnegative CHECK (position >= 0),
    CONSTRAINT ck_scenario_steps_kind CHECK (kind IN ('API_CASE', 'HTTP', 'SQL', 'REDIS', 'CONDITION', 'LOOP', 'WAIT', 'CLEANUP')),
    CONSTRAINT ck_scenario_steps_title_nonempty CHECK (btrim(title) <> ''),
    CONSTRAINT ck_scenario_steps_section CHECK (section IN ('MAIN', 'CLEANUP')),
    CONSTRAINT ck_scenario_steps_reference_mode CHECK (reference_mode IS NULL OR reference_mode IN ('REFERENCE', 'COPY')),
    CONSTRAINT ck_scenario_steps_failure_strategy CHECK (failure_strategy IN ('STOP', 'CONTINUE', 'RETRY')),
    CONSTRAINT ck_scenario_steps_config_object CHECK (jsonb_typeof(step_config) = 'object'),
    CONSTRAINT fk_scenario_steps_scenario FOREIGN KEY (project_id, scenario_id)
        REFERENCES scenarios (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_scenario_steps_parent FOREIGN KEY (scenario_id, parent_id)
        REFERENCES scenario_steps (scenario_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_scenario_steps_api_case FOREIGN KEY (project_id, api_case_id)
        REFERENCES api_cases (project_id, id) ON DELETE RESTRICT
);

CREATE INDEX ix_scenario_steps_order ON scenario_steps (scenario_id, parent_id, position, id);
