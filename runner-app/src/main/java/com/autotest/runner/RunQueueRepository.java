package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/** 单 Runner 使用的 JDBC 队列操作；领取依靠数据库行锁保证原子性。 */
public final class RunQueueRepository {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunQueueRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<RunRecord> claimPending(String jmeterVersion) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<RunRecord> pending = selectPending(connection);
                if (pending.isEmpty()) {
                    connection.commit();
                    return Optional.empty();
                }
                RunRecord run = pending.get();
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE runs SET status = 'RUNNING', started_at = now(), "
                                + "jmeter_version = ?, cancel_requested = FALSE WHERE id = ? AND status = 'PENDING'")) {
                    update.setString(1, jmeterVersion);
                    update.setObject(2, run.id());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                connection.commit();
                return findById(connection, run.id());
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("领取运行任务失败", exception);
        }
    }

    public Optional<RunRecord> findById(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            return findById(connection, id);
        } catch (SQLException exception) {
            throw new IllegalStateException("查询运行任务失败", exception);
        }
    }

    public int recoverRunning() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE runs SET status = 'INTERRUPTED', finished_at = now() WHERE status = 'RUNNING'")) {
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("恢复遗留运行失败", exception);
        }
    }

    public boolean isCancelRequested(UUID id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT cancel_requested FROM runs WHERE id = ? AND status = 'RUNNING'")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询取消状态失败", exception);
        }
    }

    public Optional<RunRecord> finish(UUID id, ProcessResult processResult, String jmxPath,
                                      String jtlPath, String logPath) {
        String status = processResult.canceled() ? "CANCELED"
                : processResult.exitCode() == 0 ? "PASSED" : "FAILED";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE runs SET status = ?, finished_at = now(), exit_code = ?, jmx_path = ?, "
                        + "jtl_path = ?, log_path = ? WHERE id = ? AND status = 'RUNNING'")) {
            statement.setString(1, status);
            statement.setInt(2, processResult.exitCode());
            statement.setString(3, jmxPath);
            statement.setString(4, jtlPath);
            statement.setString(5, logPath);
            statement.setObject(6, id);
            statement.executeUpdate();
            return findById(id);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存运行结果失败", exception);
        }
    }

    private Optional<RunRecord> selectPending(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, status, jmeter_version, started_at, finished_at, cancel_requested, exit_code, "
                        + "jmx_path, jtl_path, log_path, execution_plan FROM runs WHERE status = 'PENDING' "
                        + "ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private Optional<RunRecord> findById(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, status, jmeter_version, started_at, finished_at, cancel_requested, exit_code, "
                        + "jmx_path, jtl_path, log_path, execution_plan FROM runs WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private RunRecord map(ResultSet result) throws SQLException {
        JsonNode plan;
        try {
            plan = objectMapper.readTree(result.getString("execution_plan"));
        } catch (Exception exception) {
            throw new IllegalStateException("运行计划 JSON 无效", exception);
        }
        Timestamp started = result.getTimestamp("started_at");
        Timestamp finished = result.getTimestamp("finished_at");
        return new RunRecord(result.getObject("id", UUID.class), result.getString("status"),
                result.getString("jmeter_version"), started == null ? null : started.toInstant(),
                finished == null ? null : finished.toInstant(), result.getBoolean("cancel_requested"),
                (Integer) result.getObject("exit_code"), result.getString("jmx_path"),
                result.getString("jtl_path"), result.getString("log_path"), plan);
    }
}
