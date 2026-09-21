package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 从平台原生报告生成可下载的脱敏导出物。 */
@Service
public class ReportExportService {

    private final ReportService reports;
    private final ObjectMapper json;

    public ReportExportService(ReportService reports, ObjectMapper json) {
        this.reports = reports;
        this.json = json;
    }

    public byte[] html(UUID projectId, UUID runId) {
        RunReport report = reports.get(projectId, runId);
        RunRecord run = report.run();
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
                .append("<title>运行报告 ").append(escape(run.id().toString())).append("</title>")
                .append("<style>body{font-family:system-ui,sans-serif;margin:2rem;color:#172033}" +
                        "table{border-collapse:collapse;width:100%}th,td{border:1px solid #d9e0ea;padding:.5rem;text-align:left}" +
                        ".status{font-weight:700}</style></head><body>")
                .append("<h1>运行报告</h1><p>运行 ID：").append(escape(run.id().toString()))
                .append("</p><p>状态：<span class=\"status\">").append(escape(run.status())).append("</span></p>")
                .append("<table><thead><tr><th>步骤</th><th>状态</th><th>耗时(ms)</th><th>请求</th><th>响应</th><th>断言</th></tr></thead><tbody>");
        for (StepResultRecord step : report.steps()) {
            html.append("<tr><td>").append(escape(step.resultKey())).append("</td><td>")
                    .append(escape(step.status())).append("</td><td>").append(step.durationMs())
                    .append("</td><td><pre>").append(escape(step.requestSummary().toPrettyString()))
                    .append("</pre></td><td><pre>").append(escape(step.responseSummary().toPrettyString()))
                    .append("</pre></td><td><pre>").append(escape(step.assertions().toPrettyString()))
                    .append("</pre></td></tr>");
        }
        html.append("</tbody></table></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] allureZip(UUID projectId, UUID runId) {
        RunReport report = reports.get(projectId, runId);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                for (StepResultRecord step : report.steps()) {
                    ObjectNode result = json.createObjectNode();
                    result.put("uuid", UUID.nameUUIDFromBytes((runId + ":" + step.resultKey()).getBytes(StandardCharsets.UTF_8)).toString());
                    result.put("name", step.resultKey());
                    result.put("historyId", step.stepId().toString());
                    result.put("status", allureStatus(step.status()));
                    result.put("start", epochMillis(step.startedAt(), step.createdAt()));
                    result.put("stop", epochMillis(step.finishedAt(), step.createdAt()) + step.durationMs());
                    result.set("statusDetails", json.createObjectNode().put("message", step.errorSummary().path("message").asText("")));
                    ArrayNode labels = result.putArray("labels");
                    labels.addObject().put("name", "runId").put("value", runId.toString());
                    put(zip, step.resultKey() + "-result.json", result.toString());
                }
                ObjectNode executor = json.createObjectNode().put("name", "AI 接口自动化平台").put("buildName", runId.toString());
                put(zip, "executor.json", executor.toString());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("生成 Allure 导出失败", exception);
        }
    }

    private static String allureStatus(String status) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "PASSED" -> "passed";
            case "SKIPPED" -> "skipped";
            case "CANCELED", "INTERRUPTED" -> "broken";
            default -> "failed";
        };
    }

    private static long epochMillis(Instant primary, Instant fallback) {
        return (primary == null ? fallback : primary).toEpochMilli();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name + (name.endsWith(".json") ? "" : "")));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
