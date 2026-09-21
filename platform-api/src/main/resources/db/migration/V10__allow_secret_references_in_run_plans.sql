CREATE OR REPLACE FUNCTION autotest_has_plain_sensitive_value(value JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    entry RECORD;
    normalized_key TEXT;
    text_value TEXT;
BEGIN
    IF value IS NULL OR value = 'null'::JSONB THEN
        RETURN FALSE;
    END IF;

    IF jsonb_typeof(value) = 'object' THEN
        FOR entry IN SELECT e.key, e.value AS child FROM jsonb_each(value) AS e(key, value) LOOP
            normalized_key := regexp_replace(lower(entry.key), '[_-]', '', 'g');
            IF normalized_key IN ('password', 'secret', 'secretkey', 'token', 'accesstoken',
                                  'refreshtoken', 'apikey', 'authorization', 'cookie',
                                  'cookies', 'setcookie', 'xapikey')
               AND jsonb_typeof(entry.child) = 'string' THEN
                text_value := entry.child #>> '{}';
                IF text_value !~ '^\$\{secret:[A-Za-z0-9][A-Za-z0-9._-]*\}$'
                   AND text_value !~ '^(Bearer|Basic) \$\{secret:[A-Za-z0-9][A-Za-z0-9._-]*\}$' THEN
                    RETURN TRUE;
                END IF;
            END IF;
            IF autotest_has_plain_sensitive_value(entry.child) THEN
                RETURN TRUE;
            END IF;
        END LOOP;
    ELSIF jsonb_typeof(value) = 'array' THEN
        FOR entry IN SELECT a.value AS child FROM jsonb_array_elements(value) AS a(value) LOOP
            IF autotest_has_plain_sensitive_value(entry.child) THEN
                RETURN TRUE;
            END IF;
        END LOOP;
    END IF;
    RETURN FALSE;
END;
$$;

ALTER TABLE runs DROP CONSTRAINT ck_runs_execution_plan_no_secret_keys;

ALTER TABLE runs
    ADD CONSTRAINT ck_runs_execution_plan_no_plain_sensitive_values CHECK (
        NOT autotest_has_plain_sensitive_value(execution_plan)
    );
