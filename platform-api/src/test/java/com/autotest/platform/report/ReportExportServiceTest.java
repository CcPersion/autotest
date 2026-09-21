package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportExportServiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void htmlEscapesReportContentAndKeepsStoredSensitiveMasking() throws Exception {
        UUID runId = UUID.randomUUID();
        RunReport report = report(runId, "<script>alert(1)</script>", "***");
        ReportService reports = mock(ReportService.class);
        UUID projectId = UUID.randomUUID();
        when(reports.get(projectId, runId)).thenReturn(report);
        ReportExportService exports = new ReportExportService(reports, json);

        String html = new String(exports.html(projectId, runId), StandardCharsets.UTF_8);

        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertFalse(html.contains("plain-secret"));
    }

    @Test
    void allureZipContainsOneResultPerStepAndMappedStatus() throws Exception {
        UUID runId = UUID.randomUUID();
        RunReport report = report(runId, "step", "***");
        ReportService reports = mock(ReportService.class);
        UUID projectId = UUID.randomUUID();
        when(reports.get(projectId, runId)).thenReturn(report);
        ReportExportService exports = new ReportExportService(reports, json);

        byte[] zip = exports.allureZip(projectId, runId);
        String resultJson = readFirstResult(zip);

        assertTrue(resultJson.contains("\"status\":\"failed\""));
        assertTrue(resultJson.contains("\"name\":\"step-1\""));
        assertFalse(resultJson.contains("plain-secret"));
    }

    private RunReport report(UUID runId, String message, String masked) throws Exception {
        JsonNode request = json.readTree("{\"url\":\"http://example.test\"}");
        JsonNode response = json.readTree("{\"message\":\"" + message.replace("\"", "\\\"") + "\",\"token\":\"" + masked + "\"}");
        StepResultRecord step = new StepResultRecord(UUID.randomUUID(), runId, UUID.randomUUID(), "step-1", 0,
                "FAILED", 12, request, response, json.readTree("[{\"passed\":false}]") ,
                json.readTree("[]"), json.readTree("{\"message\":\"failed\"}"), Instant.now(), Instant.now(), Instant.now());
        RunRecord run = new RunRecord(runId, UUID.randomUUID(), UUID.randomUUID(), "API_CASE", UUID.randomUUID(),
                UUID.randomUUID(), "FAILED", json.readTree("{\"planId\":\"plan\"}"), "export-1", "5.6.3",
                Instant.now(), Instant.now(), 1, null, null, null, false, Instant.now());
        return new RunReport(run, List.of(step));
    }

    private String readFirstResult(byte[] zip) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().endsWith("-result.json")) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    input.transferTo(output);
                    return output.toString(StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("missing allure result");
    }
}
