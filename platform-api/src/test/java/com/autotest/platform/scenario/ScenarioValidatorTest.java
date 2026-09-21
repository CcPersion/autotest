package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOrderedApiCaseAndCleanupSteps() throws Exception {
        UUID first = UUID.randomUUID();
        UUID cleanup = UUID.randomUUID();
        ScenarioWrite write = new ScenarioWrite("订单链路", "", mapper.readTree("{}"), mapper.readTree("{}"), List.of(
                new ScenarioStepWrite(first, null, 0, "API_CASE", "创建订单", true, "MAIN", "REFERENCE",
                        UUID.randomUUID(), "STOP", mapper.readTree("{}")),
                new ScenarioStepWrite(cleanup, null, 1, "CLEANUP", "删除订单", true, "CLEANUP", null,
                        null, "CONTINUE", mapper.readTree("{}"))
        ), null);

        assertDoesNotThrow(() -> ScenarioValidator.validate(write));
    }

    @Test
    void rejectsDuplicatePositionMissingParentAndCycle() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ScenarioWrite duplicate = new ScenarioWrite("订单链路", null, mapper.readTree("{}"), mapper.readTree("{}"), List.of(
                step(first, null, 0), step(second, null, 0)), null);
        ScenarioWrite missingParent = new ScenarioWrite("订单链路", null, mapper.readTree("{}"), mapper.readTree("{}"),
                List.of(step(first, UUID.randomUUID(), 0)), null);
        ScenarioWrite cycle = new ScenarioWrite("订单链路", null, mapper.readTree("{}"), mapper.readTree("{}"), List.of(
                step(first, second, 0), step(second, first, 1)), null);

        assertThrows(RuntimeException.class, () -> ScenarioValidator.validate(duplicate));
        assertThrows(RuntimeException.class, () -> ScenarioValidator.validate(missingParent));
        assertThrows(RuntimeException.class, () -> ScenarioValidator.validate(cycle));
    }

    @Test
    void validatesCustomHttpPlanShape() throws Exception {
        UUID stepId = UUID.randomUUID();
        ScenarioWrite valid = new ScenarioWrite("健康检查", null, mapper.readTree("{}"), mapper.readTree("{}"),
                List.of(new ScenarioStepWrite(stepId, null, 0, "HTTP", "检查健康", true, "MAIN", null,
                        null, "STOP", mapper.readTree("{\"plan\":{\"method\":\"GET\",\"urlTemplate\":\"/actuator/health\"}}"))), null);
        assertDoesNotThrow(() -> ScenarioValidator.validate(valid));

        ScenarioWrite missingUrl = new ScenarioWrite("健康检查", null, mapper.readTree("{}"), mapper.readTree("{}"),
                List.of(new ScenarioStepWrite(stepId, null, 0, "HTTP", "检查健康", true, "MAIN", null,
                        null, "STOP", mapper.readTree("{\"plan\":{\"method\":\"GET\"}}"))), null);
        assertThrows(RuntimeException.class, () -> ScenarioValidator.validate(missingUrl));
    }

    @Test
    void validatesRetryContractAndBounds() throws Exception {
        UUID stepId = UUID.randomUUID();
        ScenarioWrite valid = new ScenarioWrite("重试", null, mapper.readTree("{}"), mapper.readTree("{}"),
                List.of(new ScenarioStepWrite(stepId, null, 0, "HTTP", "重试请求", true, "MAIN", null,
                        null, "RETRY", mapper.readTree("{\"plan\":{\"method\":\"GET\",\"urlTemplate\":\"/retry\"},\"retry\":{\"maxAttempts\":3,\"intervalMillis\":100}}"))), null);
        assertDoesNotThrow(() -> ScenarioValidator.validate(valid));
        ScenarioWrite invalid = new ScenarioWrite("重试", null, mapper.readTree("{}"), mapper.readTree("{}"),
                List.of(new ScenarioStepWrite(stepId, null, 0, "HTTP", "重试请求", true, "MAIN", null,
                        null, "RETRY", mapper.readTree("{\"plan\":{\"method\":\"GET\",\"urlTemplate\":\"/retry\"},\"retry\":{\"maxAttempts\":6,\"intervalMillis\":100}}"))), null);
        assertThrows(RuntimeException.class, () -> ScenarioValidator.validate(invalid));
    }

    private ScenarioStepWrite step(UUID id, UUID parentId, int position) throws Exception {
        return new ScenarioStepWrite(id, parentId, position, "WAIT", "等待", true, "MAIN", null,
                null, "STOP", mapper.readTree("{\"millis\":100}"));
    }
}
