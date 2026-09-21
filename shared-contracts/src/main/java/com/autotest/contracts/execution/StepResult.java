package com.autotest.contracts.execution;

import com.autotest.contracts.util.ContractChecks;

import java.util.List;

/** JMeter 运行中一个平台步骤的结构化结果。 */
public record StepResult(
        String stepId,
        String status,
        String message,
        long durationMillis,
        List<AttachmentRef> attachments
) {

    public StepResult {
        stepId = ContractChecks.requiredText(stepId, "stepId");
        status = ContractChecks.requiredText(status, "status");
        message = message == null ? "" : message;
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能为负数");
        }
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
