ALTER TABLE projects
    ADD COLUMN target_allowlist_json JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE projects
    ADD CONSTRAINT ck_projects_target_allowlist_array
        CHECK (jsonb_typeof(target_allowlist_json) = 'array');

ALTER TABLE projects
    ADD CONSTRAINT ck_projects_target_allowlist_size
        CHECK (jsonb_array_length(target_allowlist_json) <= 100);
