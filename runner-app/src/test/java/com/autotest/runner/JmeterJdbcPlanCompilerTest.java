package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmeterJdbcPlanCompilerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void compilesPreparedJdbcSamplerWithoutWritingPasswordIntoJmx() throws Exception {
        Path runDirectory = Files.createTempDirectory("autotest-jdbc-jmx-");
        Path output = runDirectory.resolve("scenario-step-1.jmx");
        var plan = json.readTree("""
                {
                  "stepId":"step-1",
                  "databaseType":"POSTGRESQL",
                  "host":"db",
                  "port":5432,
                  "databaseName":"orders",
                  "username":"runner",
                  "credentialRef":"${secret:db-password}",
                  "sql":"SELECT id FROM orders WHERE tenant_id = ${tenant}",
                  "variableScopes":{"environment":{"tenant":"environment"},"dataRow":{"tenant":"row"},"extracted":{}}
                }
                """);
        try (var secrets = new SecretFileMaterializer(runDirectory, UUID.randomUUID(),
                (project, name) -> "db-password-value")) {
            new JmeterPlanCompiler().compileJdbc(plan, secrets, Map.of(), output);
        }

        String xml = Files.readString(output);
        assertTrue(xml.contains("JDBCSampler"));
        assertTrue(xml.contains("JDBCDataSource"));
        assertTrue(xml.contains("Prepared Select Statement"));
        assertTrue(xml.contains("SELECT id FROM orders WHERE tenant_id = ?"));
        assertTrue(xml.contains("__autotestSecret"));
        assertFalse(xml.contains("db-password-value"));
        assertFalse(xml.contains("JSR223"));
        assertFalse(xml.contains("BeanShell"));
        assertFalse(xml.contains("OSProcess"));
    }

}
