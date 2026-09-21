package com.autotest.contracts.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuiltinFunctionContractTest {

    @Test
    void acceptsRootLocalePatternsIncludingUppercaseYear() {
        assertDoesNotThrow(() -> BuiltinFunctionContract.validateFormatDatePattern("yyyy-MM-dd"));
        assertDoesNotThrow(() -> BuiltinFunctionContract.validateFormatDatePattern("YYYY-MM-dd"));
    }

    @Test
    void rejectsBlankAndUnpairedOptionalSectionPatterns() {
        assertThrows(IllegalArgumentException.class, () -> BuiltinFunctionContract.validateFormatDatePattern(null));
        assertThrows(IllegalArgumentException.class, () -> BuiltinFunctionContract.validateFormatDatePattern(" "));
        assertThrows(IllegalArgumentException.class, () -> BuiltinFunctionContract.validateFormatDatePattern("yyyy-MM-dd["));
        assertThrows(IllegalArgumentException.class, () -> BuiltinFunctionContract.validateFormatDatePattern("yyyy-MM-dd]"));
    }
}
