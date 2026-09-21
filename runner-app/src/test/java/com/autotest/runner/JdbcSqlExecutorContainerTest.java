package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSqlExecutorContainerTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("autotest").withUsername("autotest").withPassword("test-password");
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("autotest").withUsername("autotest").withPassword("test-password");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void queriesPostgresqlAndExtractsColumn() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sql_probe (id INTEGER PRIMARY KEY, status VARCHAR(32))");
            statement.execute("DELETE FROM sql_probe");
            statement.execute("INSERT INTO sql_probe VALUES (7, 'PAID')");
        }
        assertQuery("localhost", POSTGRES.getFirstMappedPort(), "POSTGRESQL", POSTGRES.getUsername(), 7);
    }

    @Test
    void queriesMysqlAndChecksRowCount() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sql_probe (id INT PRIMARY KEY, status VARCHAR(32))");
            statement.execute("DELETE FROM sql_probe");
            statement.execute("INSERT INTO sql_probe VALUES (8, 'PAID')");
        }
        assertQuery("localhost", MYSQL.getFirstMappedPort(), "MYSQL", MYSQL.getUsername(), 8);
    }

    private void assertQuery(String host, int port, String type, String username, int id) throws Exception {
        var plan = json.createObjectNode();
        plan.put("stepId", UUID.randomUUID().toString()); plan.put("databaseType", type);
        plan.put("host", host); plan.put("port", port);
        plan.put("databaseName", "autotest"); plan.put("username", username);
        plan.put("credentialRef", "${secret:db}"); plan.put("sql", "SELECT id, status FROM sql_probe WHERE id = ${id}");
        ObjectNode scopes = plan.putObject("variableScopes"); scopes.putObject("environment").put("id", id); scopes.putObject("scenario");
        plan.putObject("parameters");
        var assertions = plan.putArray("assertions"); assertions.addObject().put("type", "ROW_COUNT").put("expected", 1);
        var extractors = plan.putArray("extractors"); extractors.addObject().put("column", "status").put("variable", "dbStatus");
        var directory = Files.createTempDirectory("autotest-jdbc-test-");
        try (var secrets = new SecretFileMaterializer(directory, UUID.randomUUID(), (project, name) -> "test-password")) {
            JdbcSqlExecutor.Result result = new JdbcSqlExecutor().execute(plan, secrets);
            assertTrue(result.sample().success(), result.sample().failureMessage());
            assertTrue(result.extracted().containsKey("dbStatus"));
        } finally {
            Files.deleteIfExists(directory);
        }
    }
}
