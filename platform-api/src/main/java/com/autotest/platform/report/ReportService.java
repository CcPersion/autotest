package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class ReportService {

    private static final Set<String> STATUSES = Set.of("RUNNING", "PASSED", "FAILED", "SKIPPED", "CANCELED", "INTERRUPTED");
    private final RunRepository runs;
    private final StepResultRepository results;

    public ReportService(RunRepository runs, StepResultRepository results) {
        this.runs = runs;
        this.results = results;
    }

    public RunReport get(UUID projectId, UUID runId) {
        RunRecord run = runs.findById(projectId, runId);
        if (run == null) {
            throw notFound();
        }
        return new RunReport(run, results.findByRunId(runId));
    }

    public StepResultRecord receive(UUID runId, StepResultWrite write) {
        if (runs.findById(runId) == null) {
            throw notFound();
        }
        validate(write);
        return results.upsert(runId, ReportSanitizer.sanitize(write));
    }

    private static void validate(StepResultWrite write) {
        if (write == null || write.stepId() == null || write.resultKey() == null || write.resultKey().isBlank()
                || write.resultKey().length() > 256 || write.sequenceNo() < 0 || write.durationMs() < 0
                || !STATUSES.contains(write.status()) || write.requestSummary() == null
                || !write.requestSummary().isObject() || write.responseSummary() == null
                || !write.responseSummary().isObject() || write.assertions() == null
                || !write.assertions().isArray() || write.extractions() == null || !write.extractions().isArray()
                || write.errorSummary() == null || !write.errorSummary().isObject()) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "步骤结果格式不正确");
        }
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "运行不存在");
    }
}
