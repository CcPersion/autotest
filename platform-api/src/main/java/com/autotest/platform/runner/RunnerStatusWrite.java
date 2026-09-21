package com.autotest.platform.runner;

import java.util.UUID;

public record RunnerStatusWrite(String runnerVersion, String jmeterVersion, UUID activeRunId, int queueDepth) {
}
