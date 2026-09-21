package com.autotest.platform.runner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RunnerStatusRepository {
    private final JdbcTemplate jdbc;

    public RunnerStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RunnerStatusRecord upsert(RunnerStatusWrite write, UUID runnerId, Instant now) {
        jdbc.update("INSERT INTO runner_status (runner_id, runner_version, jmeter_version, active_run_id, queue_depth, "
                        + "last_seen_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (runner_id) DO UPDATE SET runner_version = EXCLUDED.runner_version, "
                        + "jmeter_version = EXCLUDED.jmeter_version, active_run_id = EXCLUDED.active_run_id, "
                        + "queue_depth = EXCLUDED.queue_depth, last_seen_at = EXCLUDED.last_seen_at, updated_at = EXCLUDED.updated_at",
                runnerId, write.runnerVersion(), write.jmeterVersion(), write.activeRunId(), write.queueDepth(),
                Timestamp.from(now), Timestamp.from(now));
        return find(runnerId);
    }

    public RunnerStatusRecord find(UUID runnerId) {
        List<RunnerStatusRecord> rows = jdbc.query(select() + " WHERE runner_id = ?", (rs, rowNum) -> map(rs), runnerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RunnerStatusRecord> findAll() {
        return jdbc.query(select() + " ORDER BY runner_id", (rs, rowNum) -> map(rs));
    }

    private String select() {
        return "SELECT runner_id, runner_version, jmeter_version, active_run_id, queue_depth, last_seen_at, updated_at "
                + "FROM runner_status";
    }

    private static RunnerStatusRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunnerStatusRecord(rs.getObject("runner_id", UUID.class), rs.getString("runner_version"),
                rs.getString("jmeter_version"), rs.getObject("active_run_id", UUID.class),
                rs.getInt("queue_depth"), rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
