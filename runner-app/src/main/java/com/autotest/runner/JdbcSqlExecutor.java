package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL 场景步骤的受控 JDBC 执行器；只接收平台生成的结构化计划。 */
final class JdbcSqlExecutor {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private final ObjectMapper json = new ObjectMapper();

    Result execute(JsonNode plan, SecretFileMaterializer secrets) throws Exception {
        return execute(plan, secrets, Map.of());
    }

    Result execute(JsonNode plan, SecretFileMaterializer secrets,
                   Map<String, JsonNode> extractedOverride) throws Exception {
        RunVariableContext context = RunVariableContext.fromPlan("jdbc-step", plan);
        if (extractedOverride != null) context.putExtractedAll(extractedOverride);
        return execute(plan, secrets, context);
    }

    Result execute(JsonNode plan, SecretFileMaterializer secrets,
                   RunVariableContext context) throws Exception {
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("JDBC SQL 计划必须是对象");
        RunVariableContext.PreflightResult preflight = context.preflight(plan, "sqlStep");
        if (!preflight.valid()) throw new IllegalArgumentException(preflight.code() + ": " + preflight.path());
        String sql = text(plan, "sql");
        if (!"SELECT".equals(firstKeyword(sql)) && !(plan.path("allowWrite").asBoolean(false) && plan.path("confirmed").asBoolean(false))) {
            throw new IllegalArgumentException("写 SQL 未完成双确认");
        }
        String ref = text(plan, "credentialRef");
        String password = secrets == null ? null : secrets.resolveText(ref);
        String url = jdbcUrl(plan);
        BoundSql bound = bind(sql, context);
        long started = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(url, text(plan, "username"), password);
             PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int index = 0; index < bound.values().size(); index++) setValue(statement, index + 1, bound.values().get(index));
            boolean query = statement.execute();
            List<Map<String, JsonNode>> rows = query ? readRows(statement.getResultSet()) : List.of();
            Map<String, JsonNode> extracted = extract(plan.path("extractors"), rows);
            context.putExtractedAll(extracted);
            List<String> failures = assertRows(plan.path("assertions"), rows, context);
            boolean success = failures.isEmpty();
            String message = success ? "SQL" : "SQL 断言失败";
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String extractionJson = extractionJson(extracted);
            return new Result(new JtlSample(plan.path("stepId").asText("SQL"), elapsed, success ? 200 : 422,
                    message, success, String.join("\n", failures), "jdbc:" + plan.path("databaseType").asText(""), extractionJson), extracted);
        } catch (SQLException exception) {
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new Result(new JtlSample(plan.path("stepId").asText("SQL"), elapsed, 500, "SQL 执行失败", false,
                    "SQL 执行失败", "jdbc:" + plan.path("databaseType").asText(""), "[]"), Map.of());
        }
    }

    private BoundSql bind(String sql, JsonNode scopes, Map<String, JsonNode> extractedOverride) {
        RunVariableContext context = RunVariableContext.builder("jdbc-bind")
                .environment(scope(scopes, "environment"))
                .scenario(scope(scopes, "scenario"))
                .caseVariables(scope(scopes, "caseVariables"))
                .dataRow(scope(scopes, "dataRow"))
                .extracted(merge(scope(scopes, "extracted"), extractedOverride))
                .build();
        return bind(sql, context);
    }

    BoundSql bind(String sql, RunVariableContext context) {
        Matcher matcher = VARIABLE.matcher(sql); StringBuffer output = new StringBuffer(); List<JsonNode> args = new ArrayList<>();
        while (matcher.find()) {
            JsonNode value = context.lookup(matcher.group(1));
            if (value == null) throw new RunVariableContext.VariableUndefinedException(matcher.group(1), "sql");
            matcher.appendReplacement(output, "?"); args.add(value);
        }
        matcher.appendTail(output);
        return new BoundSql(output.toString(), args);
    }

    private Map<String, JsonNode> merge(Map<String, JsonNode> first, Map<String, JsonNode> second) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (first != null) result.putAll(first);
        if (second != null) second.forEach(result::put);
        return result;
    }

    private Map<String, JsonNode> scope(JsonNode scopes, String name) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        JsonNode node = scopes == null ? null : scopes.get(name);
        if (node != null && node.isObject()) node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private List<Map<String, JsonNode>> readRows(ResultSet resultSet) throws SQLException {
        List<Map<String, JsonNode>> rows = new ArrayList<>(); ResultSetMetaData meta = resultSet.getMetaData(); int count = meta.getColumnCount();
        while (resultSet.next()) { Map<String, JsonNode> row = new LinkedHashMap<>(); for (int i = 1; i <= count; i++) row.put(meta.getColumnLabel(i), json.valueToTree(resultSet.getObject(i))); rows.add(row); }
        return rows;
    }

    private Map<String, JsonNode> extract(JsonNode config, List<Map<String, JsonNode>> rows) {
        Map<String, JsonNode> result = new LinkedHashMap<>(); if (!config.isArray()) return result;
        for (JsonNode item : config) { String variable = item.path("variable").asText(""); String column = item.path("column").asText(""); int row = item.path("rowIndex").asInt(0); if (!variable.isBlank() && !column.isBlank() && row >= 0 && row < rows.size() && rows.get(row).containsKey(column)) result.put(variable, rows.get(row).get(column)); else if (item.path("failIfMissing").asBoolean(true)) throw new IllegalArgumentException("SQL 提取结果缺失"); }
        return result;
    }

    private List<String> assertRows(JsonNode config, List<Map<String, JsonNode>> rows, RunVariableContext context) {
        List<String> failures = new ArrayList<>(); if (!config.isArray()) return failures;
        for (JsonNode item : config) { String type = item.path("type").asText("").toUpperCase(); JsonNode expected = context.resolveNode(item.get("expected"));
            if ("ROW_COUNT".equals(type)) { int want = expected == null ? -1 : expected.asInt(-1); if (want != rows.size()) failures.add("行数期望 " + want + "，实际 " + rows.size()); }
            else if ("FIELD".equals(type)) { String column = item.path("column").asText(""); String actual = rows.isEmpty() ? null : String.valueOf(rows.get(0).get(column)); if (expected == null || actual == null || !actual.equals(expected.toString()) && !actual.equals(expected.asText())) failures.add("字段断言失败: " + column); }
            else if ("COLLECTION_CONTAINS".equals(type)) { String column = item.path("column").asText(""); String want = expected == null ? "" : expected.asText(); boolean found = rows.stream().anyMatch(row -> want.equals(String.valueOf(row.get(column))) || want.equals(row.get(column) == null ? null : row.get(column).asText())); if (!found) failures.add("集合不包含期望值: " + column); }
            else failures.add("不支持的 SQL 断言类型: " + type);
        } return failures;
    }

    private String extractionJson(Map<String, JsonNode> extracted) { ArrayNode array = json.createArrayNode(); extracted.forEach((name, value) -> { ObjectNode item = array.addObject(); item.put("variable", name); item.put("type", "SQL_COLUMN"); item.put("matched", true); item.set("value", value); }); return array.toString(); }
    private static void setValue(PreparedStatement statement, int index, JsonNode value) throws SQLException { if (value == null || value.isNull()) statement.setObject(index, null); else if (value.isBoolean()) statement.setBoolean(index, value.asBoolean()); else if (value.isIntegralNumber()) statement.setLong(index, value.asLong()); else if (value.isFloatingPointNumber()) statement.setDouble(index, value.asDouble()); else statement.setString(index, value.asText()); }
    private static String jdbcUrl(JsonNode plan) { String type = text(plan, "databaseType"); String scheme = "MYSQL".equalsIgnoreCase(type) ? "jdbc:mysql" : "jdbc:postgresql"; return scheme + "://" + text(plan, "host") + ":" + plan.path("port").asInt() + "/" + text(plan, "databaseName"); }
    private static String firstKeyword(String sql) { String value = sql.replaceAll("(?s)^\\s*(--[^\\n]*(\\n|$)|/\\*.*?\\*/\\s*)+", "").strip(); int i = 0; while (i < value.length() && Character.isLetter(value.charAt(i))) i++; return value.substring(0, i).toUpperCase(); }
    private static String text(JsonNode node, String field) { String value = node.path(field).asText("").strip(); if (value.isBlank()) throw new IllegalArgumentException("JDBC SQL 计划缺少 " + field); return value; }
    record Result(JtlSample sample, Map<String, JsonNode> extracted) {}
    record BoundSql(String sql, List<JsonNode> values) {}
}
