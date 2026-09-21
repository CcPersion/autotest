CREATE TABLE test_suites (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    environment_id UUID,
    revision INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_test_suites_project_id_id UNIQUE (project_id, id),
    CONSTRAINT ck_test_suites_name_nonempty CHECK (btrim(name) <> ''),
    CONSTRAINT ck_test_suites_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT fk_test_suites_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT,
    CONSTRAINT fk_test_suites_environment_same_project FOREIGN KEY (project_id, environment_id)
        REFERENCES environments (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_test_suites_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_test_suites_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_test_suites_active_project_name
    ON test_suites (project_id, lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE test_suite_members (
    id UUID PRIMARY KEY,
    suite_id UUID NOT NULL,
    project_id UUID NOT NULL,
    position INTEGER NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_test_suite_members_suite_id_id UNIQUE (suite_id, id),
    CONSTRAINT uq_test_suite_members_suite_position UNIQUE (suite_id, position),
    CONSTRAINT ck_test_suite_members_position_nonnegative CHECK (position >= 0),
    CONSTRAINT ck_test_suite_members_target_type CHECK (target_type IN ('API_CASE', 'SCENARIO')),
    CONSTRAINT fk_test_suite_members_suite FOREIGN KEY (project_id, suite_id)
        REFERENCES test_suites (project_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_test_suite_members_order ON test_suite_members (suite_id, position, id);
