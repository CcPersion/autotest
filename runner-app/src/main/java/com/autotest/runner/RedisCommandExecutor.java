package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redis 场景步骤的受控执行器；命令集合固定，不接受 Lua 或任意命令文本。 */
final class RedisCommandExecutor {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private final ObjectMapper json = new ObjectMapper();

    Result execute(JsonNode plan, SecretFileMaterializer secrets, Map<String, JsonNode> extractedOverride) {
        RunVariableContext context = RunVariableContext.fromPlan("redis-step", plan);
        if (extractedOverride != null) context.putExtractedAll(extractedOverride);
        return execute(plan, secrets, context);
    }

    Result execute(JsonNode plan, SecretFileMaterializer secrets, RunVariableContext context) {
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("Redis 计划必须是对象");
        RunVariableContext.PreflightResult preflight = context.preflight(plan, "redisStep");
        if (!preflight.valid()) throw new IllegalArgumentException(preflight.code() + ": " + preflight.path());
        String command = text(plan, "command").toUpperCase(java.util.Locale.ROOT);
        if (!List.of("GET", "SET", "DEL", "EXISTS").contains(command)) throw new IllegalArgumentException("Redis 命令不在白名单");
        if ((command.equals("SET") || command.equals("DEL"))
                && !(plan.path("allowWrite").asBoolean(false) && plan.path("confirmed").asBoolean(false))) {
            throw new IllegalArgumentException("Redis 写操作未完成二次确认");
        }
        String key = resolveValue(text(plan, "key"), context);
        String value = plan.has("value") ? resolveValue(scalar(plan.get("value")), context) : null;
        String ref = plan.path("credentialRef").asText("");
        String password = ref.isBlank() || secrets == null ? null : secrets.resolveText(ref);
        boolean tls = plan.path("options").path("tls").asBoolean(false);
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .database(plan.path("databaseNumber").asInt(0)).ssl(tls);
        if (!plan.path("username").asText("").isBlank()) config.user(plan.path("username").asText());
        if (password != null) config.password(password);
        long started = System.nanoTime();
        try (JedisPooled jedis = new JedisPooled(new HostAndPort(text(plan, "host"), plan.path("port").asInt()), config.build())) {
            JsonNode result;
            if (command.equals("GET")) result = textNode(jedis.get(key));
            else if (command.equals("SET")) result = textNode(jedis.set(key, value));
            else if (command.equals("DEL")) result = LongNode.valueOf(jedis.del(key));
            else result = BooleanNode.valueOf(jedis.exists(key));
            Map<String, JsonNode> extracted = extract(plan.path("extractors"), result, command);
            context.putExtractedAll(extracted);
            List<String> failures = assertResult(plan.path("assertions"), result, command, context);
            boolean success = failures.isEmpty();
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new Result(new JtlSample(plan.path("stepId").asText("REDIS"), elapsed, success ? 200 : 422,
                    "REDIS " + command, success, String.join("\n", failures), "redis:" + command, extractionJson(extracted)), extracted);
        } catch (RuntimeException exception) {
            long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            return new Result(new JtlSample(plan.path("stepId").asText("REDIS"), elapsed, 500, "Redis 执行失败",
                    false, "Redis 执行失败", "redis:" + command, "[]"), Map.of());
        }
    }

    private Map<String, JsonNode> extract(JsonNode config, JsonNode result, String command) {
        Map<String, JsonNode> output = new LinkedHashMap<>();
        if (config == null || !config.isArray()) return output;
        for (JsonNode item : config) {
            String variable = item.path("variable").asText("").strip();
            String source = item.path("source").asText("value").toLowerCase(java.util.Locale.ROOT);
            JsonNode value = switch (source) {
                case "value" -> result;
                case "exists" -> command.equals("EXISTS") ? result : BooleanNode.valueOf(!result.isNull());
                case "command" -> TextNode.valueOf(command);
                default -> null;
            };
            if (!variable.isBlank() && value != null && !value.isNull()) output.put(variable, value.deepCopy());
            else if (item.path("failIfMissing").asBoolean(true)) throw new IllegalArgumentException("Redis 提取结果缺失");
        }
        return output;
    }

    private List<String> assertResult(JsonNode config, JsonNode result, String command, RunVariableContext context) {
        List<String> failures = new ArrayList<>();
        if (config == null || !config.isArray()) return failures;
        for (JsonNode item : config) {
            String type = item.path("type").asText("").toUpperCase(java.util.Locale.ROOT);
            boolean actualExists = !result.isNull() && !(result.isBoolean() && !result.asBoolean());
            JsonNode expected = context.resolveNode(item.get("expected"));
            if (type.equals("EXISTS") && actualExists != expected.asBoolean(true)) failures.add("存在性断言失败");
            else if (type.equals("EQUALS") && !scalar(result).equals(scalar(expected))) failures.add("值相等断言失败");
            else if (type.equals("CONTAINS") && !scalar(result).contains(scalar(expected))) failures.add("值包含断言失败");
            else if (!List.of("EXISTS", "EQUALS", "CONTAINS").contains(type)) failures.add("不支持的 Redis 断言类型: " + type);
        }
        return failures;
    }

    String resolveValue(String source, RunVariableContext context) {
        RunVariableContext.PreflightResult preflight = context.preflight(TextNode.valueOf(source), "redis");
        if (!preflight.valid()) throw new IllegalArgumentException(preflight.code() + ": " + preflight.path());
        return context.resolveText(source);
    }
    private static JsonNode textNode(String value) { return value == null ? com.fasterxml.jackson.databind.node.NullNode.instance : TextNode.valueOf(value); }
    private static String scalar(JsonNode value) { return value == null || value.isNull() ? "" : value.isTextual() ? value.textValue() : value.toString(); }
    private static String text(JsonNode plan, String field) { String value = plan.path(field).asText("").strip(); if (value.isBlank()) throw new IllegalArgumentException("Redis 计划缺少 " + field); return value; }
    private String extractionJson(Map<String, JsonNode> extracted) { ArrayNode array = json.createArrayNode(); extracted.forEach((name, value) -> { ObjectNode item = array.addObject(); item.put("variable", name); item.put("type", "REDIS_VALUE"); item.put("matched", true); item.set("value", value); }); return array.toString(); }
    record Result(JtlSample sample, Map<String, JsonNode> extracted) {}
}
