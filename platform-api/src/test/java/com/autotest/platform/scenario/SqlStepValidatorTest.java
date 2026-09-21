package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlStepValidatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onlySelectIsQueryAndUnknownIsWrite() {
        assertEquals(SqlStepValidator.Classification.SELECT, SqlStepValidator.classify(" /* comment */ SELECT 1"));
        assertEquals(SqlStepValidator.Classification.WRITE, SqlStepValidator.classify("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"));
        assertEquals(SqlStepValidator.Classification.WRITE, SqlStepValidator.classify("DROP TABLE t"));
    }

    @Test
    void unconfirmedWriteIsRejected() throws Exception {
        var config = json.readTree("{\"dataSourceId\":\"00000000-0000-0000-0000-000000000001\",\"sql\":\"UPDATE orders SET status='PAID'\"}");
        assertThrows(RuntimeException.class, () -> SqlStepValidator.validate(config, "stepConfig"));
        var confirmed = json.readTree("{\"dataSourceId\":\"00000000-0000-0000-0000-000000000001\",\"sql\":\"UPDATE orders SET status='PAID'\",\"allowWrite\":true,\"confirmed\":true}");
        assertDoesNotThrow(() -> SqlStepValidator.validate(confirmed, "stepConfig"));
    }
}
