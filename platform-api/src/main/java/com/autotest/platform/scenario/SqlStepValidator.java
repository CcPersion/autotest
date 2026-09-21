package com.autotest.platform.scenario;

import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** SQL 步骤的保守分类和结构校验；未知语句默认按写操作处理。 */
public final class SqlStepValidator {
    private static final Set<String> WRITE_KEYWORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "ALTER", "CREATE", "DROP", "TRUNCATE", "CALL", "DO", "GRANT", "REVOKE", "REPLACE");
    private SqlStepValidator() {}

    public enum Classification { SELECT, WRITE }

    public static Classification classify(String sql) {
        String first = firstKeyword(sql);
        return "SELECT".equals(first) ? Classification.SELECT : Classification.WRITE;
    }

    public static void validate(JsonNode config, String field) {
        if (config == null || !config.isObject()) throw invalid(field, "SQL 步骤配置必须是对象");
        String source = config.path("dataSourceId").asText("");
        try { UUID.fromString(source); } catch (IllegalArgumentException e) { throw invalid(field + ".dataSourceId", "SQL 步骤必须选择数据源"); }
        String sql = config.path("sql").asText("").strip();
        if (sql.isBlank() || sql.length() > 100_000) throw invalid(field + ".sql", "SQL 文本不能为空且不能超过 100000 个字符");
        JsonNode parameters = config.get("parameters");
        if (parameters != null && !parameters.isObject()) throw invalid(field + ".parameters", "SQL 参数必须是对象");
        validateArray(config.get("extractors"), field + ".extractors", "提取器");
        validateArray(config.get("assertions"), field + ".assertions", "断言");
        if (classify(sql) == Classification.WRITE && !(config.path("allowWrite").asBoolean(false) && config.path("confirmed").asBoolean(false))) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "WRITE_SQL_CONFIRMATION_REQUIRED", "写 SQL 必须勾选允许写入并完成二次确认");
        }
    }

    private static void validateArray(JsonNode node, String field, String label) {
        if (node == null) return;
        if (!node.isArray()) throw invalid(field, label + "必须是数组");
        for (JsonNode item : node) {
            if (!item.isObject()) throw invalid(field, label + "项必须是对象");
        }
    }

    private static String firstKeyword(String sql) {
        String normalized = sql == null ? "" : sql.replaceAll("(?s)^\\s*(--[^\\n]*(\\n|$)|/\\*.*?\\*/\\s*)+", "").strip();
        int end = 0;
        while (end < normalized.length() && Character.isLetter(normalized.charAt(end))) end++;
        return normalized.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static ApiDomainException invalid(String field, String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_SQL_STEP", message, java.util.Map.of("field", field));
    }
}
