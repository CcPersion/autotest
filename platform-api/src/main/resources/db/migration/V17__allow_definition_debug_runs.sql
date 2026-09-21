ALTER TABLE runs DROP CONSTRAINT IF EXISTS ck_runs_target_type;
ALTER TABLE runs ADD CONSTRAINT ck_runs_target_type
    CHECK (target_type IN ('API_CASE', 'API_DEFINITION', 'SCENARIO', 'TEST_SUITE'));
