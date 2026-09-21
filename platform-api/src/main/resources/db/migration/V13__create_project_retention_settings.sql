CREATE TABLE project_retention_settings (
    project_id UUID PRIMARY KEY,
    retention_days INTEGER NOT NULL DEFAULT 30,
    revision INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_project_retention_days CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_project_retention_revision CHECK (revision >= 0),
    CONSTRAINT fk_project_retention_project FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT
);
