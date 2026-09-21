package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.autotest.platform.report.ReportService;
import com.autotest.platform.report.RunReport;
import com.autotest.platform.report.StepResultRecord;
import com.autotest.platform.run.RunRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelConfigWriteOnlyCarriesSecretReference() {
        AiModelConfigWrite write = new AiModelConfigWrite("test", "fake://model", "fixture-model",
                "model-key", true, null);
        assertEquals("model-key", write.apiKeySecretRef());
        assertFalse(write.toString().contains("secret-value"));
    }

    @Test
    void sanitizerMasksSensitiveValuesButKeepsSecretReference() {
        String sanitized = AiPromptSanitizer.sanitize(
                "Authorization: Bearer super-secret password=raw-pass token=raw-token ${secret:api-key}");
        assertFalse(sanitized.contains("super-secret"));
        assertFalse(sanitized.contains("raw-pass"));
        assertFalse(sanitized.contains("raw-token"));
        assertTrue(sanitized.contains("${secret:api-key}"));
    }

    @Test
    void registryExposesOnlyStructuredDomainTools() throws Exception {
        AiToolRegistry registry = new AiToolRegistry(mapper, null, null, null, null);
        List<AiToolDefinition> tools = registry.definitions();
        assertEquals(List.of("list_projects", "list_api_cases", "list_scenarios", "get_run_report",
                "create_draft_patch"), tools.stream().map(AiToolDefinition::name).toList());
        assertTrue(tools.stream().noneMatch(tool -> tool.name().contains("database")
                || tool.name().contains("jmeter") || tool.name().contains("file")
                || tool.name().contains("shell") || tool.name().contains("runner")));
    }

    @Test
    void draftPatchIsReturnedWithoutPersistingAnything() throws Exception {
        AiToolRegistry registry = new AiToolRegistry(mapper, null, null, null, null);
        var arguments = mapper.createObjectNode()
                .put("projectId", UUID.randomUUID().toString())
                .put("targetType", "API_CASE")
                .put("title", "登录用例");
        var operations = mapper.createArrayNode();
        operations.addObject().put("op", "add").put("path", "/assertions/0").put("value", "STATUS=200");
        arguments.set("operations", operations);
        JsonNode result = registry.invoke("create_draft_patch", arguments);
        assertEquals("PENDING_REVIEW", result.path("status").asText());
        assertNotNull(result.path("patchId").textValue());
        assertEquals(1, result.path("operations").size());
    }

    @Test
    void fakeModelProducesToolCallWithoutAccessToForbiddenTools() {
        FakeChatModelClient client = new FakeChatModelClient();
        List<AiStreamEvent> events = client.complete(new AiModelConfigRecord(UUID.randomUUID(), "fake",
                "fake://model", "fixture", null, true, 0, null, null, null),
                List.of(new AiChatMessage("user", "请列出当前项目")), List.of(
                        new AiToolDefinition("list_projects", "列项目", mapper.createObjectNode())));
        assertEquals("TOOL_CALL", events.get(0).type());
        assertEquals("list_projects", events.get(0).toolName());
        assertTrue(events.stream().noneMatch(event -> event.content() != null && event.content().contains("secret-value")));
    }

    @Test
    void fakeModelProducesCurlPatchToolCall() {
        FakeChatModelClient client = new FakeChatModelClient();
        List<AiStreamEvent> events = client.complete(new AiModelConfigRecord(UUID.randomUUID(), "fake",
                "fake://model", "fixture", null, true, 0, null, null, null),
                List.of(new AiChatMessage("user", "请解析这段 Curl")),
                List.of(new AiToolDefinition("create_draft_patch", "生成 Patch", mapper.createObjectNode())));
        assertEquals("TOOL_CALL", events.get(0).type());
        assertEquals("create_draft_patch", events.get(0).toolName());
        assertEquals("API_DEFINITION", events.get(0).arguments().path("targetType").asText());
        assertTrue(events.get(0).arguments().path("operations").size() >= 4);
    }

    @Test
    void reportToolReturnsSanitizedEvidenceForFailureExplanation() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        StepResultRecord step = new StepResultRecord(UUID.randomUUID(), runId, stepId, "orders.create", 0,
                "FAILED", 123, mapper.readTree("{\"url\":\"/orders?token=raw-token\",\"authorization\":\"Bearer raw\"}"),
                mapper.readTree("{\"body\":{\"token\":\"raw-token\"}}"),
                mapper.readTree("[{\"type\":\"STATUS\",\"expected\":200,\"actual\":500}]"),
                mapper.readTree("[]"), mapper.readTree("{\"message\":\"upstream failed\"}"),
                Instant.now(), Instant.now(), Instant.now());
        RunRecord run = new RunRecord(runId, UUID.randomUUID(), UUID.randomUUID(), "API_CASE", UUID.randomUUID(),
                UUID.randomUUID(), "FAILED", mapper.createObjectNode(), "idempotency", "5.6.3", null, null,
                1, null, null, null, false, Instant.now());
        ReportService reports = Mockito.mock(ReportService.class);
        Mockito.when(reports.get(Mockito.any(), Mockito.eq(runId))).thenReturn(new RunReport(run, List.of(step)));
        AiToolRegistry registry = new AiToolRegistry(mapper, null, null, null, reports);

        JsonNode result = registry.invoke("get_run_report", mapper.createObjectNode()
                .put("projectId", run.projectId().toString()).put("runId", runId.toString()));

        JsonNode item = result.path("steps").get(0);
        assertEquals("FAILED", item.path("status").asText());
        assertTrue(item.has("assertions"));
        assertEquals("***", item.path("response").path("body").path("token").asText());
        assertFalse(result.toString().contains("raw-token"));
    }
}
