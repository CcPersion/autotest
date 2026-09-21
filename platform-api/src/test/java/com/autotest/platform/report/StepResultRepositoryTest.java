package com.autotest.platform.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class StepResultRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    @Test
    void repeatedResultKeyReturnsOneStableResult() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        createTables(jdbc, runId);
        StepResultRepository repository = new StepResultRepository(jdbc, new ObjectMapper());
        StepResultWrite write = new StepResultWrite(stepId, "step-1", 0, "PASSED", 24,
                new ObjectMapper().readTree("{\"method\":\"GET\"}"),
                new ObjectMapper().readTree("{\"statusCode\":200}"),
                new ObjectMapper().readTree("[]"), new ObjectMapper().readTree("{}"),
                Instant.parse("2026-09-12T00:00:00Z"), Instant.parse("2026-09-12T00:00:00Z"));

        StepResultRecord first = repository.upsert(runId, write);
        StepResultRecord repeated = repository.upsert(runId, write);

        assertEquals(first.id(), repeated.id());
        assertEquals(1, repository.findByRunId(runId).size());
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private void createTables(JdbcTemplate jdbc, UUID runId) {
        jdbc.execute("CREATE TABLE runs (id UUID PRIMARY KEY)");
        jdbc.update("INSERT INTO runs (id) VALUES (?)", runId);
        jdbc.execute("""
                CREATE TABLE step_results (
                  id UUID PRIMARY KEY, run_id UUID NOT NULL, step_id UUID NOT NULL,
                  result_key VARCHAR(256) NOT NULL, sequence_no INTEGER NOT NULL,
                  status VARCHAR(32) NOT NULL, duration_ms BIGINT NOT NULL,
                  request_summary JSONB NOT NULL, response_summary JSONB NOT NULL,
                  assertions_json JSONB NOT NULL, extractions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                  error_summary JSONB NOT NULL,
                  started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
                  UNIQUE(run_id, result_key), FOREIGN KEY(run_id) REFERENCES runs(id))
                """);
    }
}
