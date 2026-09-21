package com.autotest.contracts.execution;

import com.autotest.contracts.util.ContractChecks;

/** 运行产物的不可变索引，不携带附件内容本身。 */
public record AttachmentRef(
        String attachmentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String storageKey
) {

    public AttachmentRef {
        attachmentId = ContractChecks.requiredText(attachmentId, "attachmentId");
        fileName = ContractChecks.requiredText(fileName, "fileName");
        contentType = ContractChecks.requiredText(contentType, "contentType");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数");
        }
        storageKey = ContractChecks.requiredText(storageKey, "storageKey");
    }
}
