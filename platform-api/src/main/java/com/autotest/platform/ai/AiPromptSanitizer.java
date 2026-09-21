package com.autotest.platform.ai;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** 在模型边界统一替换常见敏感值，保留显式密钥引用占位符。 */
public final class AiPromptSanitizer {
    private static final int MAX_CONTEXT_LENGTH = 20_000;
    private static final String TRUNCATION_MARKER = "…[已截断]";
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(Bearer|Basic)\\s+[^\\s,}]+(?=\\s|$)");
    private static final Pattern SENSITIVE_JSON_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"'](?:password|secret|token|api[_-]?key|cookie|authorization)[\\\"']\\s*:\\s*[\\\"'])(?!\\$\\{secret:)([^\\\"']*)([\\\"'])");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?<!\\$\\{)\\b(?:password|secret|token|api[_-]?key|cookie|authorization)\\b\\s*[:=]\\s*[\\\"']?)([^\\s,;}\\\"']+)");

    private AiPromptSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String sanitized = AUTHORIZATION.matcher(value)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + " ${secret:redacted}"));
        sanitized = SENSITIVE_ASSIGNMENT.matcher(sanitized)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "${secret:redacted}"));
        sanitized = SENSITIVE_JSON_ASSIGNMENT.matcher(sanitized)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "${secret:redacted}" + match.group(3)));
        if (sanitized.length() > MAX_CONTEXT_LENGTH) {
            sanitized = sanitized.substring(0, MAX_CONTEXT_LENGTH - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
        }
        return sanitized;
    }
}
