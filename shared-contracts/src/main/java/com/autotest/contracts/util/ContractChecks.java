package com.autotest.contracts.util;

import java.util.Objects;

/** 共享契约的最小构造校验工具。 */
public final class ContractChecks {

    private ContractChecks() {
    }

    public static String requiredText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }
}
