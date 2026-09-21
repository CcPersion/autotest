package com.autotest.platform.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs/{runId}/exports")
public class ReportExportController {

    private final ReportExportService exports;

    public ReportExportController(ReportExportService exports) {
        this.exports = exports;
    }

    @GetMapping(value = "/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable UUID projectId, @PathVariable UUID runId) {
        String body = new String(exports.html(projectId, runId), StandardCharsets.UTF_8);
        HttpHeaders headers = headers(MediaType.TEXT_HTML, body.getBytes(StandardCharsets.UTF_8).length,
                "run-" + runId + ".html");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping(value = "/allure", produces = "application/zip")
    public ResponseEntity<byte[]> allure(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return download(exports.allureZip(projectId, runId), MediaType.parseMediaType("application/zip"),
                "run-" + runId + "-allure.zip");
    }

    private static ResponseEntity<byte[]> download(byte[] bytes, MediaType mediaType, String filename) {
        return ResponseEntity.ok().headers(headers(mediaType, bytes.length, filename)).body(bytes);
    }

    private static HttpHeaders headers(MediaType mediaType, int length, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentLength(length);
        headers.set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        return headers;
    }
}
