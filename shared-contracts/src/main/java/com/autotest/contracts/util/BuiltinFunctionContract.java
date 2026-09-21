package com.autotest.contracts.util;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 平台与 Runner 共同使用的内置函数格式合同。 */
public final class BuiltinFunctionContract {

    public static final String DEFAULT_FORMAT_DATE_PATTERN = "yyyy-MM-dd";

    private BuiltinFunctionContract() {
    }

    /**
     * 返回统一 Locale.ROOT 语义的日期格式器，并校验合同要求的输入边界。
     *
     * <p>不能把空白模式交给 DateTimeFormatter：空格本身是合法的字面量，
     * 但不是平台允许的有效格式。方括号也在进入 JDK 解析前显式检查配对，
     * 使不同组件不会因 JDK 解析细节而产生漂移。</p>
     */
    public static DateTimeFormatter formatDateFormatter(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("日期格式不能为空");
        }
        validateOptionalSectionBrackets(pattern);
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
    }

    public static void validateFormatDatePattern(String pattern) {
        formatDateFormatter(pattern);
    }

    private static void validateOptionalSectionBrackets(String pattern) {
        int depth = 0;
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '[') {
                depth++;
            } else if (character == ']') {
                if (depth == 0) {
                    throw new IllegalArgumentException("日期格式可选段未配对");
                }
                depth--;
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("日期格式可选段未配对");
        }
    }
}
