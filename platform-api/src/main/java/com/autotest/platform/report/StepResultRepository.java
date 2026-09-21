package com.autotest.platform.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class StepResultRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StepResultRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public StepResultRecord upsert(UUID runId, StepResultWrite write) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO step_results (id, run_id, step_id, result_key, sequence_no, status, duration_ms,
                  request_summary, response_summary, assertions_json, extractions_json, error_summary, started_at, finished_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT (run_id, result_key) DO NOTHING
                """, id, runId, write.stepId(), write.resultKey(), write.sequenceNo(), write.status(),
                write.durationMs(), write.requestSummary().toString(), write.responseSummary().toString(),
                write.assertions().toString(), write.extractions().toString(), write.errorSummary().toString(), timestamp(write.startedAt()),
                timestamp(write.finishedAt()), Timestamp.from(now));
        return findByKey(runId, write.resultKey());
    }

    public List<StepResultRecord> findByRunId(UUID runId) {
        return jdbc.query(select() + " WHERE run_id = ? ORDER BY sequence_no, created_at, id",
                (rs, rowNum) -> map(rs), runId);
    }

    private StepResultRecord findByKey(UUID runId, String resultKey) {
        List<StepResultRecord> rows = jdbc.query(select() + " WHERE run_id = ? AND result_key = ?",
                (rs, rowNum) -> map(rs), runId, resultKey);
        if (rows.isEmpty()) {
            throw new IllegalStateException("保存步骤结果后无法查询记录");
        }
        return rows.get(0);
    }

    private String select() {
        return "SELECT id, run_id, step_id, result_key, sequence_no, status, duration_ms, request_summary, "
                + "response_summary, assertions_json, extractions_json, error_summary, started_at, finished_at, created_at "
                + "FROM step_results";
    }

    private StepResultRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new StepResultRecord(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                    rs.getObject("step_id", UUID.class), rs.getString("result_key"), rs.getInt("sequence_no"),
                    rs.getString("status"), rs.getLong("duration_ms"), json.readTree(rs.getString("request_summary")),
                    json.readTree(rs.getString("response_summary")), json.readTree(rs.getString("assertions_json")),
                    json.readTree(rs.getString("extractions_json")), json.readTree(rs.getString("error_summary")), instant(rs, "started_at"),
                    instant(rs, "finished_at"), rs.getTimestamp("created_at").toInstant());
        } catch (IOException exception) {
            throw new IllegalStateException("步骤结果 JSON 无效", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
