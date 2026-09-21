ALTER TABLE step_results
    ADD COLUMN extractions_json JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE step_results
    ADD CONSTRAINT ck_step_results_extractions_array
    CHECK (jsonb_typeof(extractions_json) = 'array');
