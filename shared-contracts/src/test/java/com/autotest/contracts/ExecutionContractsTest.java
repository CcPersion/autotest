package com.autotest.contracts;

import com.autotest.contracts.execution.AttachmentRef;
import com.autotest.contracts.execution.ExecutionPlan;
import com.autotest.contracts.execution.RunEvent;
import com.autotest.contracts.execution.RunTask;
import com.autotest.contracts.execution.StepResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionContractsTest {

    private static final String SECRET_SENTINEL = "super-secret-value-that-must-not-be-persisted";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Instant createdAt = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void executionPlanDeepCopiesNestedJsonAndRoundTripsEveryJsonValueType() throws Exception {
        ObjectNode nested = mapper.createObjectNode().put("name", "original");
        ArrayNode assetItems = mapper.createArrayNode().add(LongNode.valueOf(7L)).addNull();
        ObjectNode assetDocument = mapper.createObjectNode()
                .put("count", 7L)
                .put("ratio", new BigDecimal("1.2500"))
                .put("enabled", true)
                .put("name", "example")
                .set("nested", nested);
        assetDocument.set("items", assetItems);
        assetDocument.set("optional", NullNode.getInstance());
        Map<String, JsonNode> assetContent = new LinkedHashMap<>();
        assetContent.put("document", assetDocument);

        ExecutionPlan plan = newPlan(assetContent, jsonValues(), List.of("${secret:token}"), Map.of());

        assetDocument.put("afterConstruct", "not-visible");
        nested.put("changed", "not-visible");
        assetItems.add("not-visible");
        assetContent.put("extra", TextNode.valueOf("not-visible"));

        assertFalse(plan.assetContent().get("document").has("afterConstruct"));
        assertFalse(plan.assetContent().get("document").get("nested").has("changed"));
        assertEquals(2, plan.assetContent().get("document").get("items").size());
        assertFalse(plan.assetContent().containsKey("extra"));

        Map<String, JsonNode> exposed = plan.assetContent();
        ((ObjectNode) exposed.get("document")).put("returnedMutation", "not-visible");
        ((ArrayNode) exposed.get("document").get("items")).add("not-visible");

        assertFalse(plan.assetContent().get("document").has("returnedMutation"));
        assertEquals(2, plan.assetContent().get("document").get("items").size());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.assetContent().put("other", TextNode.valueOf("value")));

        ExecutionPlan restored = mapper.readValue(mapper.writeValueAsBytes(plan), ExecutionPlan.class);

        assertEquals(plan, restored);
        assertEquals(plan.assetContent(), restored.assetContent());
        assertEquals(plan.variables(), restored.variables());
    }

    @Test
    void variablesKeepStringNumberBooleanObjectArrayAndNullTypesAndAreDeeplyImmutable() {
        Map<String, JsonNode> variables = jsonValues();
        ExecutionPlan plan = newPlan(Map.of(), variables, List.of("${secret:token}"), Map.of());

        ((ObjectNode) variables.get("object")).put("changed", "not-visible");
        ((ArrayNode) variables.get("array")).add("not-visible");
        variables.put("changed", TextNode.valueOf("not-visible"));

        Map<String, JsonNode> exposed = plan.variables();
        ((ObjectNode) exposed.get("object")).put("returnedMutation", "not-visible");
        ((ArrayNode) exposed.get("array")).add("not-visible");

        assertEquals(JsonNodeType.STRING, plan.variables().get("string").getNodeType());
        assertTrue(plan.variables().get("long").isNumber());
        assertTrue(plan.variables().get("decimal").isNumber());
        assertTrue(plan.variables().get("boolean").isBoolean());
        assertTrue(plan.variables().get("object").isObject());
        assertEquals(2, plan.variables().get("array").size());
        assertTrue(plan.variables().get("null").isNull());
        assertFalse(plan.variables().get("object").has("changed"));
        assertFalse(plan.variables().get("object").has("returnedMutation"));
        assertEquals(2, plan.variables().get("array").size());
        assertFalse(plan.variables().containsKey("changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.variables().put("other", TextNode.valueOf("value")));
    }

    @Test
    void secretRefsRequirePlaceholdersAndSensitiveJsonKeysCannotStorePlaintext() throws Exception {
        ExecutionPlan plan = newPlan(
                Map.of("credentials", mapper.createObjectNode().put("authorization", "${secret:auth-token}")),
                Map.of("profile", mapper.createObjectNode().set("apiKey", TextNode.valueOf("${secret:api-key}"))),
                List.of("${secret:token}", "${secret:auth-token}"), Map.of());

        String json = mapper.writeValueAsString(plan);

        assertTrue(json.contains("${secret:token}"));
        assertFalse(json.contains(SECRET_SENTINEL));
        assertThrows(IllegalArgumentException.class,
                () -> newPlan(Map.of(), Map.of(), List.of(SECRET_SENTINEL), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> newPlan(Map.of(), Map.of(), List.of("secret:token"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> newPlan(Map.of(), Map.of(), List.of("${secret:}"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> newPlan(Map.of(), Map.of("password", TextNode.valueOf(SECRET_SENTINEL)), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> newPlan(Map.of("nested", mapper.createObjectNode().put("token", SECRET_SENTINEL)),
                        Map.of(), List.of(), Map.of()));
        assertDoesNotThrow(() -> newPlan(
                Map.of("nested", mapper.createObjectNode().put("token", "${secret:token}")),
                Map.of("nested", mapper.createObjectNode().put("authorization", "${secret:auth}")),
                List.of(), Map.of()));
    }

    @Test
    void rejectsPlaintextAuthorizationHeaderLinesButAllowsBearerSecretReferences() throws Exception {
        ObjectNode plaintextHeader = mapper.createObjectNode()
                .put("name", "Authorization")
                .put("value", SECRET_SENTINEL);
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("headers", mapper.createArrayNode().add(plaintextHeader)),
                Map.of(), List.of(), Map.of()));

        ObjectNode safeHeader = mapper.createObjectNode()
                .put("name", "Authorization")
                .put("value", "Bearer ${secret:token}");
        ExecutionPlan plan = assertDoesNotThrow(() -> newPlan(
                Map.of("headers", mapper.createArrayNode().add(safeHeader)),
                Map.of(), List.of(), Map.of()));

        assertFalse(mapper.writeValueAsString(plan).contains(SECRET_SENTINEL));
        assertFalse(plan.toString().contains(SECRET_SENTINEL));
    }

    @Test
    void directAuthorizationOnlyAllowsExactSafeTemplates() {
        assertDoesNotThrow(() -> newPlan(
                Map.of("authorization", TextNode.valueOf("Bearer ${secret:token}")),
                Map.of(), List.of(), Map.of()));
        assertDoesNotThrow(() -> newPlan(
                Map.of("Authorization", TextNode.valueOf("Basic ${secret:basic-auth}")),
                Map.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("authorization", TextNode.valueOf("Bearer " + SECRET_SENTINEL)),
                Map.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("authorization", TextNode.valueOf("Bearer ${secret:token} trailing")),
                Map.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("authorization", TextNode.valueOf("Token ${secret:token}")),
                Map.of(), List.of(), Map.of()));
    }

    @Test
    void cookiesRejectPlaintextAndAllowOnlyStructuredSecretReferences() {
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("cookie", TextNode.valueOf(SECRET_SENTINEL)),
                Map.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("cookies", TextNode.valueOf(SECRET_SENTINEL)),
                Map.of(), List.of(), Map.of()));

        ObjectNode plaintextCookie = mapper.createObjectNode()
                .put("name", "session")
                .put("value", SECRET_SENTINEL);
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("cookies", mapper.createArrayNode().add(plaintextCookie)),
                Map.of(), List.of(), Map.of()));

        ObjectNode safeCookie = mapper.createObjectNode()
                .put("name", "session")
                .put("value", "${secret:session}");
        ObjectNode safeCookieHeader = mapper.createObjectNode()
                .put("name", "Cookie")
                .put("value", "${secret:cookie-header}");
        assertDoesNotThrow(() -> newPlan(
                Map.of(
                        "cookies", mapper.createArrayNode().add(safeCookie),
                        "headers", mapper.createArrayNode().add(safeCookieHeader)),
                Map.of(), List.of(), Map.of()));
    }

    @Test
    void rejectsPlaintextSensitiveApiKeyHeaderLines() {
        ObjectNode apiKeyHeader = mapper.createObjectNode()
                .put("name", "X-Api-Key")
                .put("value", SECRET_SENTINEL);
        ObjectNode apiKeyAliasHeader = mapper.createObjectNode()
                .put("name", "API-Key")
                .put("value", SECRET_SENTINEL);

        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("headers", mapper.createArrayNode().add(apiKeyHeader)),
                Map.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> newPlan(
                Map.of("headers", mapper.createArrayNode().add(apiKeyAliasHeader)),
                Map.of(), List.of(), Map.of()));
    }

    @Test
    void ordinaryTokenizerVariableIsNotTreatedAsTokenSecret() {
        assertDoesNotThrow(() -> newPlan(
                Map.of(), Map.of("tokenizer", TextNode.valueOf("standard")), List.of(), Map.of()));
    }

    @Test
    void ordinaryHeadersObjectInsideBusinessBodyIsNotTreatedAsHeaderLines() {
        ObjectNode body = mapper.createObjectNode();
        body.set("headers", mapper.createObjectNode().put("display", "value"));

        assertDoesNotThrow(() -> newPlan(
                Map.of("body", body), Map.of(), List.of(), Map.of()));
    }

    @Test
    void executionPlanCollectionsDefensivelyCopyInputsAndRejectMutation() {
        Map<String, JsonNode> assets = new LinkedHashMap<>();
        assets.put("value", TextNode.valueOf("original"));
        Map<String, JsonNode> variables = new LinkedHashMap<>();
        variables.put("value", TextNode.valueOf("original"));
        List<String> secretRefs = new ArrayList<>(List.of("${secret:token}"));
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put("payload.json", "sha256:abc");
        ExecutionPlan plan = newPlan(assets, variables, secretRefs, checksums);

        assets.put("changed", TextNode.valueOf("not-visible"));
        variables.put("changed", TextNode.valueOf("not-visible"));
        secretRefs.add("${secret:other}");
        checksums.put("changed", "not-visible");

        assertFalse(plan.assetContent().containsKey("changed"));
        assertFalse(plan.variables().containsKey("changed"));
        assertEquals(1, plan.secretRefs().size());
        assertFalse(plan.fileChecksums().containsKey("changed"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.secretRefs().add("${secret:other}"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.fileChecksums().put("other", "sha256:def"));
    }

    @Test
    void runEventAndStepResultDefensivelyCopyAttachmentCollections() {
        AttachmentRef attachment = attachment();
        List<AttachmentRef> eventAttachments = new ArrayList<>(List.of(attachment));
        List<AttachmentRef> resultAttachments = new ArrayList<>(List.of(attachment));
        RunEvent event = new RunEvent("event-1", "run-1", "step-finished", createdAt,
                "step-1", "ok", new StepResult("step-1", "PASSED", "ok", 42L, resultAttachments),
                eventAttachments);

        eventAttachments.clear();
        resultAttachments.clear();

        assertEquals(1, event.attachments().size());
        assertEquals(1, event.stepResult().attachments().size());
        assertThrows(UnsupportedOperationException.class, () -> event.attachments().add(attachment));
        assertThrows(UnsupportedOperationException.class,
                () -> event.stepResult().attachments().add(attachment));
    }

    @Test
    void everyDtoValidatesItsRequiredFields() {
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlan(null, Map.of(), Map.of(), List.of(), Map.of(), "5.6.3", createdAt));
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlan("plan-1", Map.of(), Map.of(), List.of(), Map.of(), null, createdAt));
        assertThrows(NullPointerException.class,
                () -> new ExecutionPlan("plan-1", Map.of(), Map.of(), List.of(), Map.of(), "5.6.3", null));
        assertThrows(NullPointerException.class,
                () -> new RunTask(null, "run-1", "plan-1", createdAt));
        assertThrows(NullPointerException.class,
                () -> new RunEvent("event-1", "run-1", null, createdAt, null, "", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new RunEvent("event-1", "run-1", "started", null, null, "", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StepResult("step-1", "", "message", 1L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StepResult("step-1", "PASSED", "message", -1L, List.of()));
        assertThrows(NullPointerException.class,
                () -> new AttachmentRef(null, "log.txt", "text/plain", 1L, "runs/log.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> new AttachmentRef("attachment-1", "log.txt", "text/plain", -1L, "runs/log.txt"));
    }

    @Test
    void runTaskRoundTripsAsJson() throws Exception {
        RunTask task = new RunTask("task-1", "run-1", "plan-1", createdAt);

        RunTask restored = mapper.readValue(mapper.writeValueAsBytes(task), RunTask.class);

        assertEquals(task, restored);
    }

    @Test
    void runEventRoundTripsWithStepResultAndAttachments() throws Exception {
        AttachmentRef attachment = attachment();
        StepResult stepResult = new StepResult("step-1", "PASSED", "ok", 42L, List.of(attachment));
        RunEvent event = new RunEvent(
                "event-1", "run-1", "step-finished", createdAt, "step-1", "ok",
                stepResult, List.of(attachment));

        RunEvent restored = mapper.readValue(mapper.writeValueAsBytes(event), RunEvent.class);

        assertEquals(event, restored);
        assertEquals("step-finished", restored.type());
        assertEquals(1, restored.attachments().size());
    }

    @Test
    void stepResultRoundTripsAsJson() throws Exception {
        StepResult result = new StepResult("step-1", "FAILED", "assertion failed", 100L, List.of());

        StepResult restored = mapper.readValue(mapper.writeValueAsBytes(result), StepResult.class);

        assertEquals(result, restored);
    }

    @Test
    void attachmentRefRoundTripsAsJson() throws Exception {
        AttachmentRef attachment = attachment();

        AttachmentRef restored = mapper.readValue(mapper.writeValueAsBytes(attachment), AttachmentRef.class);

        assertEquals(attachment, restored);
    }

    private ExecutionPlan newPlan(
            Map<String, JsonNode> assetContent,
            Map<String, JsonNode> variables,
            List<String> secretRefs,
            Map<String, String> fileChecksums) {
        return new ExecutionPlan("plan-1", assetContent, variables, secretRefs, fileChecksums, "5.6.3", createdAt);
    }

    private Map<String, JsonNode> jsonValues() {
        ObjectNode object = mapper.createObjectNode().put("nested", "value");
        ArrayNode array = mapper.createArrayNode().add(IntNode.valueOf(1)).add("value");
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("string", TextNode.valueOf("value"));
        values.put("long", LongNode.valueOf(9007199254740991L));
        values.put("decimal", DecimalNode.valueOf(new BigDecimal("1.2500")));
        values.put("boolean", BooleanNode.TRUE);
        values.put("object", object);
        values.put("array", array);
        values.put("null", NullNode.getInstance());
        return values;
    }

    private AttachmentRef attachment() {
        return new AttachmentRef("attachment-1", "response.json", "application/json", 12L,
                "runs/run-1/response.json");
    }
}
