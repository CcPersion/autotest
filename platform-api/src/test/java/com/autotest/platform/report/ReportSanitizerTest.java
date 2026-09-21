package com.autotest.platform.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSanitizerTest {

    @Test
    void masksAuthorizationLikeTextInsideArraysToo() throws Exception {
        var json = new ObjectMapper();
        StepResultWrite write = new StepResultWrite(null, "k", 0, "PASSED", 1,
                json.readTree("{}"), json.readTree("{\"body\":[\"Bearer plain-secret\"]}"),
                json.readTree("[]"), json.readTree("{}"), null, null);

        String sanitized = ReportSanitizer.sanitize(write).responseSummary().toString();

        assertFalse(sanitized.contains("plain-secret"));
    }

    @Test
    void masksSensitiveExtractionVariableValues() throws Exception {
        var json = new ObjectMapper();
        StepResultWrite write = new StepResultWrite(null, "k", 0, "PASSED", 1,
                json.readTree("{}"), json.readTree("{}"), json.readTree("[]"),
                json.readTree("[{\"variable\":\"accessToken\",\"matched\":true,\"value\":\"plain-secret\"},"
                        + "{\"variable\":\"profile\",\"value\":{\"role\":\"qa\"}}]"),
                json.readTree("{}"), null, null);

        String sanitized = ReportSanitizer.sanitize(write).extractions().toString();

        assertFalse(sanitized.contains("plain-secret"));
        assertTrue(sanitized.contains("\"role\":\"qa\""));
    }

    @Test
    void masksEncodedCaseVariantQueryKeysAtPersistenceFrontier() throws Exception {
        var json = new ObjectMapper();
        StepResultWrite write = new StepResultWrite(null, "k", 0, "PASSED", 1,
                json.readTree("{\"url\":\"https://test.local/p?ACCESS%5FTOKEN=sentinel&x%2Dapi%2Dkey=api-sentinel&tenant=demo\"}"),
                json.readTree("{}"), json.readTree("[]"), json.readTree("{}"), null, null);

        String sanitized = ReportSanitizer.sanitize(write).requestSummary().toString();

        assertFalse(sanitized.contains("sentinel"));
        assertFalse(sanitized.contains("api-sentinel"));
        assertTrue(sanitized.contains("ACCESS%5FTOKEN=***"));
        assertTrue(sanitized.contains("x%2Dapi%2Dkey=***"));
        assertTrue(sanitized.contains("tenant=demo"));
    }

    @Test
    void masksSensitiveJsonBodyTextAndTruncatesBeforePersistence() throws Exception {
        var json = new ObjectMapper();
        String oversized = "x".repeat(1_100_000);
        StepResultWrite write = new StepResultWrite(null, "k", 0, "PASSED", 1,
                json.readTree("{}"), json.readTree("{\"body\":\"{\\\"access_token\\\":\\\"sentinel\\\"}" + oversized + "\"}"),
                json.readTree("[]"), json.readTree("{}"), null, null);

        String sanitized = ReportSanitizer.sanitize(write).responseSummary().toString();

        assertFalse(sanitized.contains("sentinel"));
        assertTrue(sanitized.contains("…[已截断]"));
    }

    @Test
    void masksJsonPathAssertionActualWhenExpressionNamesSensitiveField() throws Exception {
        var json = new ObjectMapper();
        StepResultWrite write = new StepResultWrite(null, "k", 0, "PASSED", 1,
                json.readTree("{}"), json.readTree("{}"),
                json.readTree("[{\"type\":\"JSON_PATH\",\"expression\":\"$.access_token\",\"actual\":\"sentinel\",\"expected\":true,\"passed\":true}]"),
                json.readTree("[]"), json.readTree("{}"), null, null);

        String sanitized = ReportSanitizer.sanitize(write).assertions().toString();

        assertFalse(sanitized.contains("sentinel"));
        assertTrue(sanitized.contains("\"actual\":\"***\""));
    }

    @Test
    void masksKnownSensitiveValuesInsideAssertionMessagesAndErrorSummary() throws Exception {
        var json = new ObjectMapper();
        String sentinel = "f1-09-sensitive-sentinel";
        StepResultWrite write = new StepResultWrite(null, "k", 0, "FAILED", 1,
                json.readTree("{}"), json.readTree("{\"body\":{\"token\":\"" + sentinel + "\"}}"),
                json.readTree("[{\"type\":\"JSON_PATH\",\"expression\":\"$.token\",\"actual\":\"" + sentinel
                        + "\",\"message\":\"actual=" + sentinel + "\",\"passed\":false}]"),
                json.readTree("[]"), json.readTree("{\"message\":\"failure=" + sentinel
                        + "\",\"detail\":\"detail=" + sentinel + "\"}"), null, null);

        StepResultWrite sanitized = ReportSanitizer.sanitize(write);

        assertFalse(sanitized.assertions().toString().contains(sentinel));
        assertFalse(sanitized.errorSummary().toString().contains(sentinel));
        assertTrue(sanitized.assertions().toString().contains("***"));
        assertTrue(sanitized.errorSummary().toString().contains("***"));
    }

    @Test
    void masksSecretFunctionsInAllReportEvidenceTextFields() throws Exception {
        var json = new ObjectMapper();
        String requestSentinel = "request-secret-sentinel";
        String responseSentinel = "response-secret-sentinel";
        String assertionSentinel = "assertion-secret-sentinel";
        String extractionSentinel = "extraction-secret-sentinel";
        String errorSentinel = "error-secret-sentinel";
        StepResultWrite write = new StepResultWrite(null, "k", 0, "FAILED", 1,
                json.readTree("{\"note\":\"${__autotestSecret(" + requestSentinel + ")}\"}"),
                json.readTree("{\"note\":\"${__autotestSecret(" + responseSentinel + ")}\"}"),
                json.readTree("[{\"type\":\"TEXT\",\"message\":\"${__autotestSecret("
                        + assertionSentinel + ")}\",\"passed\":false}]"),
                json.readTree("[{\"variable\":\"profile\",\"value\":\"${__autotestSecret("
                        + extractionSentinel + ")}\"}]"),
                json.readTree("{\"message\":\"${__autotestSecret(" + errorSentinel + ")}\"}"),
                null, null);

        StepResultWrite sanitized = ReportSanitizer.sanitize(write);

        assertSecretFunctionRemoved(sanitized.requestSummary().toString(), requestSentinel);
        assertSecretFunctionRemoved(sanitized.responseSummary().toString(), responseSentinel);
        assertSecretFunctionRemoved(sanitized.assertions().toString(), assertionSentinel);
        assertSecretFunctionRemoved(sanitized.extractions().toString(), extractionSentinel);
        assertSecretFunctionRemoved(sanitized.errorSummary().toString(), errorSentinel);
    }

    private static void assertSecretFunctionRemoved(String sanitized, String sentinel) {
        assertFalse(sanitized.contains("${__autotestSecret("));
        assertFalse(sanitized.contains(sentinel));
    }

    @Test
    void preservesJsonNullNodesAcrossSanitizedReportFields() throws Exception {
        var json = new ObjectMapper();
        var nullNode = json.readTree("null");
        StepResultWrite write = new StepResultWrite(null, "k", 0, "PASSED", 1,
                nullNode, nullNode, nullNode, nullNode, nullNode, null, null);

        StepResultWrite sanitized = ReportSanitizer.sanitize(write);

        assertTrue(sanitized.requestSummary().isNull());
        assertTrue(sanitized.responseSummary().isNull());
        assertTrue(sanitized.assertions().isNull());
        assertTrue(sanitized.extractions().isNull());
        assertTrue(sanitized.errorSummary().isNull());
    }
}
