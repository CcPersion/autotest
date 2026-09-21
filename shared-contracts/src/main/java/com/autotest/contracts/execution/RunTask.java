package com.autotest.contracts.execution;

import com.autotest.contracts.util.ContractChecks;

import java.time.Instant;

/** Platform API 提交给 Runner 的最小运行任务。 */
public record RunTask(
        String taskId,
        String runId,
        String executionPlanId,
        Instant createdAt
) {

    public RunTask {
        taskId = ContractChecks.requiredText(taskId, "taskId");
        runId = ContractChecks.requiredText(runId, "runId");
        executionPlanId = ContractChecks.requiredText(executionPlanId, "executionPlanId");
        if (createdAt == null) {
            throw new NullPointerException("createdAt 不能为空");
        }
    }
}
