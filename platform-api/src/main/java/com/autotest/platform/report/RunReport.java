package com.autotest.platform.report;

import com.autotest.platform.run.RunRecord;

import java.util.List;

public record RunReport(RunRecord run, List<StepResultRecord> steps) {
    public RunReport {
        steps = List.copyOf(steps);
    }
}
