CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_users_username_nonempty CHECK (btrim(username) <> ''),
    CONSTRAINT ck_users_password_hash_nonempty CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_users_revision_nonnegative CHECK (revision >= 0)
);

CREATE UNIQUE INDEX ux_users_username_lower ON users (lower(username));

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_projects_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_projects_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_projects_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_projects_active_name_lower
    ON projects (lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE modules (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_id UUID,
    name VARCHAR(256) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_modules_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_modules_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_modules_sort_order_nonnegative CHECK (sort_order >= 0),
    CONSTRAINT ck_modules_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_modules_not_self_parent CHECK (parent_id IS NULL OR id <> parent_id),
    CONSTRAINT fk_modules_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_modules_parent_same_project FOREIGN KEY (project_id, parent_id)
        REFERENCES modules (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_modules_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_modules_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_modules_active_root_name
    ON modules (project_id, lower(name))
    WHERE archived_at IS NULL AND parent_id IS NULL;

CREATE UNIQUE INDEX ux_modules_active_child_name
    ON modules (project_id, parent_id, lower(name))
    WHERE archived_at IS NULL AND parent_id IS NOT NULL;

CREATE TABLE environments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    base_url TEXT NOT NULL,
    variables_json JSONB NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_environments_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_environments_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_environments_base_url_nonempty CHECK (btrim(base_url) <> ''),
    CONSTRAINT ck_environments_variables_object CHECK (jsonb_typeof(variables_json) = 'object'),
    CONSTRAINT ck_environments_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_environments_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_environments_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_environments_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_environments_active_project_name
    ON environments (project_id, lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE api_definitions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    module_id UUID,
    name VARCHAR(256) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    url_template TEXT NOT NULL,
    request_spec JSONB NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_api_definitions_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_api_definitions_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_api_definitions_method CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS')),
    CONSTRAINT ck_api_definitions_url_nonempty CHECK (btrim(url_template) <> ''),
    CONSTRAINT ck_api_definitions_request_object CHECK (jsonb_typeof(request_spec) = 'object'),
    CONSTRAINT ck_api_definitions_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_api_definitions_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_definitions_module_same_project FOREIGN KEY (project_id, module_id)
        REFERENCES modules (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_definitions_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_definitions_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_api_definitions_active_module_name
    ON api_definitions (project_id, module_id, lower(name))
    WHERE archived_at IS NULL AND module_id IS NOT NULL;

CREATE UNIQUE INDEX ux_api_definitions_active_root_name
    ON api_definitions (project_id, lower(name))
    WHERE archived_at IS NULL AND module_id IS NULL;

CREATE TABLE api_cases (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    api_definition_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    case_spec JSONB NOT NULL,
    variables_json JSONB NOT NULL,
    assertions_json JSONB NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_api_cases_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_api_cases_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_api_cases_case_object CHECK (jsonb_typeof(case_spec) = 'object'),
    CONSTRAINT ck_api_cases_variables_object CHECK (jsonb_typeof(variables_json) = 'object'),
    CONSTRAINT ck_api_cases_assertions_array CHECK (jsonb_typeof(assertions_json) = 'array'),
    CONSTRAINT ck_api_cases_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_api_cases_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_cases_definition_same_project FOREIGN KEY (project_id, api_definition_id)
        REFERENCES api_definitions (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_cases_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_api_cases_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_api_cases_active_definition_name
    ON api_cases (project_id, api_definition_id, lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE runs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    environment_id UUID NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    requested_by UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    execution_plan JSONB NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    jmeter_version VARCHAR(64),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_runs_target_type CHECK (target_type IN ('API_CASE', 'SCENARIO', 'TEST_SUITE')),
    CONSTRAINT ck_runs_status CHECK (status IN ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'CANCELED', 'INTERRUPTED')),
    CONSTRAINT ck_runs_execution_plan_object CHECK (jsonb_typeof(execution_plan) = 'object'),
    CONSTRAINT ck_runs_execution_plan_no_secret_keys CHECK (
        execution_plan::text !~* '"(password|secret|token|api[_-]?key)"[[:space:]]*:'
    ),
    CONSTRAINT ck_runs_idempotency_key_nonempty CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_runs_time_order CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT fk_runs_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_runs_environment_same_project FOREIGN KEY (project_id, environment_id)
        REFERENCES environments (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_runs_requested_by FOREIGN KEY (requested_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_runs_project_idempotency_key ON runs (project_id, idempotency_key);

CREATE TABLE step_results (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    step_id UUID NOT NULL,
    result_key VARCHAR(256) NOT NULL,
    sequence_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL,
    request_summary JSONB NOT NULL,
    response_summary JSONB NOT NULL,
    assertions_json JSONB NOT NULL,
    error_summary JSONB NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_step_results_run_result_key UNIQUE (run_id, result_key),
    CONSTRAINT ck_step_results_result_key_nonempty CHECK (btrim(result_key) <> ''),
    CONSTRAINT ck_step_results_sequence_nonnegative CHECK (sequence_no >= 0),
    CONSTRAINT ck_step_results_duration_nonnegative CHECK (duration_ms >= 0),
    CONSTRAINT ck_step_results_status CHECK (status IN ('RUNNING', 'PASSED', 'FAILED', 'SKIPPED', 'CANCELED', 'INTERRUPTED')),
    CONSTRAINT ck_step_results_request_object CHECK (jsonb_typeof(request_summary) = 'object'),
    CONSTRAINT ck_step_results_response_object CHECK (jsonb_typeof(response_summary) = 'object'),
    CONSTRAINT ck_step_results_assertions_array CHECK (jsonb_typeof(assertions_json) = 'array'),
    CONSTRAINT ck_step_results_error_object CHECK (jsonb_typeof(error_summary) = 'object'),
    CONSTRAINT ck_step_results_time_order CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT fk_step_results_run FOREIGN KEY (run_id) REFERENCES runs (id) ON DELETE RESTRICT
);
