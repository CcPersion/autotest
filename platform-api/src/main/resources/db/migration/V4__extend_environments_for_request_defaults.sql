ALTER TABLE environments
    ADD COLUMN request_options_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE environments
    ADD CONSTRAINT ck_environments_request_options_object
    CHECK (jsonb_typeof(request_options_json) = 'object');
