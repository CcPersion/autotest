package com.autotest.contracts.execution;

import com.autotest.contracts.util.ContractChecks;

import java.time.Instant;
import java.util.List;

/** Runner 以 JSON Lines 回传的平台运行事件。 */
public record RunEvent(
        String eventId,
        String runId,
        String type,
        Instant occurredAt,
        String stepId,
        String message,
        StepResult stepResult,
        List<AttachmentRef> attachments
) {

    public RunEvent {
        eventId = ContractChecks.requiredText(eventId, "eventId");
        runId = ContractChecks.requiredText(runId, "runId");
        type = ContractChecks.requiredText(type, "type");
        if (occurredAt == null) {
            throw new NullPointerException("occurredAt 不能为空");
        }
        message = message == null ? "" : message;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
