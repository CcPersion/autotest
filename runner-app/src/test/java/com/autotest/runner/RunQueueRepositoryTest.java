package com.autotest.runner;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RunQueueRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest")
            .withUsername("autotest")
            .withPassword("test-only-password");

    @Test
    void atomicallyClaimsOnePendingRunAndRecoversRunningRows() throws Exception {
        DataSource dataSource = dataSource();
        createRunsTable(dataSource);
        UUID first = insertRun(dataSource, "PENDING");
        RunQueueRepository repository = new RunQueueRepository(dataSource);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<RunRecord>>> futures = pool.invokeAll(List.of(
                    () -> repository.claimPending("5.6.3"),
                    () -> repository.claimPending("5.6.3")));
            List<RunRecord> claimed = futures.stream().map(this::get).flatMap(Optional::stream).toList();
            assertEquals(1, claimed.size());
            assertEquals("RUNNING", claimed.get(0).status());
            assertTrue(claimed.get(0).id().equals(first));

            assertEquals(1, repository.recoverRunning());
            assertEquals("INTERRUPTED", repository.findById(claimed.get(0).id()).orElseThrow().status());
        } finally {
            pool.shutdownNow();
        }
    }

    private Optional<RunRecord> get(Future<Optional<RunRecord>> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private void createRunsTable(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runs (id UUID PRIMARY KEY, project_id UUID NOT NULL, "
                    + "environment_id UUID NOT NULL, target_type VARCHAR(32) NOT NULL, target_id UUID NOT NULL, "
                    + "requested_by UUID NOT NULL, status VARCHAR(32) NOT NULL, execution_plan JSONB NOT NULL, "
                    + "idempotency_key VARCHAR(256) NOT NULL, jmeter_version VARCHAR(64), started_at TIMESTAMPTZ, "
                    + "finished_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL, exit_code INTEGER, "
                    + "jmx_path VARCHAR(512), jtl_path VARCHAR(512), log_path VARCHAR(512), "
                    + "cancel_requested BOOLEAN NOT NULL DEFAULT FALSE)");
        }
    }

    private UUID insertRun(DataSource dataSource, String status) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO runs (id, project_id, environment_id, target_type, target_id, requested_by, "
                    + "status, execution_plan, idempotency_key, created_at) VALUES ('" + id + "', '" + UUID.randomUUID()
                    + "', '" + UUID.randomUUID() + "', 'API_CASE', '" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                    + "', '" + status + "', '{}'::jsonb, '" + UUID.randomUUID() + "', now())");
        }
        return id;
    }
}
