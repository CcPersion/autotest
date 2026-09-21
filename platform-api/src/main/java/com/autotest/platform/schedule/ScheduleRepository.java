package com.autotest.platform.schedule;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ScheduleRepository {
    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ScheduleRecord> findAll(UUID projectId) {
        return jdbc.query(select() + " WHERE project_id = ? ORDER BY name, id", (rs, rowNum) -> map(rs), projectId);
    }

    public ScheduleRecord findById(UUID projectId, UUID scheduleId) {
        List<ScheduleRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, scheduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ScheduleRecord findByIdForUpdate(UUID projectId, UUID scheduleId) {
        List<ScheduleRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, scheduleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsName(UUID projectId, String name, UUID excludingId) {
        String sql = excludingId == null
                ? "SELECT EXISTS (SELECT 1 FROM schedules WHERE project_id = ? AND lower(name) = lower(?))"
                : "SELECT EXISTS (SELECT 1 FROM schedules WHERE project_id = ? AND lower(name) = lower(?) AND id <> ?)";
        Boolean exists = excludingId == null
                ? jdbc.queryForObject(sql, Boolean.class, projectId, name)
                : jdbc.queryForObject(sql, Boolean.class, projectId, name, excludingId);
        return Boolean.TRUE.equals(exists);
    }

    public ScheduleRecord insert(UUID projectId, String name, UUID suiteId, UUID environmentId, String cron,
                                 String zoneId, boolean enabled, Instant nextRunAt, UUID actorId)
            throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO schedules (id, project_id, suite_id, environment_id, name, cron_expression, zone_id, "
                        + "enabled, revision, next_run_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)", id, projectId, suiteId, environmentId,
                name, cron, zoneId, enabled, timestamp(nextRunAt), actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public int update(UUID projectId, UUID scheduleId, String name, UUID suiteId, UUID environmentId, String cron,
                      String zoneId, boolean enabled, Instant nextRunAt, int revision, UUID actorId) {
        return jdbc.update("UPDATE schedules SET name = ?, suite_id = ?, environment_id = ?, cron_expression = ?, "
                        + "zone_id = ?, enabled = ?, revision = revision + 1, next_run_at = ?, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND revision = ?",
                name, suiteId, environmentId, cron, zoneId, enabled, timestamp(nextRunAt), actorId,
                Timestamp.from(Instant.now()), projectId, scheduleId, revision);
    }

    public int advance(UUID projectId, UUID scheduleId, Instant expectedNext, Instant next, Instant lastRun) {
        return jdbc.update("UPDATE schedules SET next_run_at = ?, last_run_at = ?, updated_at = now() "
                        + "WHERE project_id = ? AND id = ? AND next_run_at = ? AND enabled = TRUE",
                timestamp(next), timestamp(lastRun), projectId, scheduleId, timestamp(expectedNext));
    }

    public List<ScheduleRecord> findDue(Instant now) {
        return jdbc.query(select() + " WHERE enabled = TRUE AND next_run_at IS NOT NULL AND next_run_at <= ? "
                        + "ORDER BY next_run_at, id", (rs, rowNum) -> map(rs), Timestamp.from(now));
    }

    public int setEnabled(UUID projectId, UUID scheduleId, boolean enabled, int revision, Instant nextRunAt,
                          UUID actorId) {
        return jdbc.update("UPDATE schedules SET enabled = ?, revision = revision + 1, next_run_at = ?, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND revision = ?", enabled, timestamp(nextRunAt),
                actorId, Timestamp.from(Instant.now()), projectId, scheduleId, revision);
    }

    private String select() {
        return "SELECT id, project_id, suite_id, environment_id, name, cron_expression, zone_id, enabled, revision, "
                + "next_run_at, last_run_at, created_by, updated_by, created_at, updated_at FROM schedules";
    }

    private static ScheduleRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScheduleRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("suite_id", UUID.class), rs.getObject("environment_id", UUID.class), rs.getString("name"),
                rs.getString("cron_expression"), rs.getString("zone_id"), rs.getBoolean("enabled"), rs.getInt("revision"),
                instant(rs, "next_run_at"), instant(rs, "last_run_at"), rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
