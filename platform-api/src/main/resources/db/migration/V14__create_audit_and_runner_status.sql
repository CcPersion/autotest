CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_id UUID,
    project_id UUID,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID,
    trace_id VARCHAR(128),
    run_id UUID,
    step_id UUID,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_audit_action_nonempty CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_resource_type_nonempty CHECK (btrim(resource_type) <> ''),
    CONSTRAINT ck_audit_metadata_object CHECK (jsonb_typeof(metadata_json) = 'object'),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE SET NULL,
    CONSTRAINT fk_audit_run FOREIGN KEY (run_id) REFERENCES runs (id) ON DELETE SET NULL,
    CONSTRAINT ck_audit_step_requires_run CHECK (step_id IS NULL OR run_id IS NOT NULL)
);

CREATE INDEX ix_audit_project_created ON audit_events (project_id, created_at DESC, id);
CREATE INDEX ix_audit_trace ON audit_events (trace_id, created_at DESC);
CREATE INDEX ix_audit_run_step ON audit_events (run_id, step_id, created_at DESC);

CREATE TABLE runner_status (
    runner_id UUID PRIMARY KEY,
    runner_version VARCHAR(64) NOT NULL,
    jmeter_version VARCHAR(64) NOT NULL,
    active_run_id UUID,
    queue_depth INTEGER NOT NULL DEFAULT 0,
    last_seen_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_runner_version_nonempty CHECK (btrim(runner_version) <> ''),
    CONSTRAINT ck_runner_jmeter_version_nonempty CHECK (btrim(jmeter_version) <> ''),
    CONSTRAINT ck_runner_queue_depth_nonnegative CHECK (queue_depth >= 0),
    CONSTRAINT fk_runner_active_run FOREIGN KEY (active_run_id) REFERENCES runs (id) ON DELETE SET NULL
);
