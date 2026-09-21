package com.autotest.platform.scenario;

import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Set;

final class RedisStepValidator {
    private static final Set<String> COMMANDS = Set.of("GET", "SET", "DEL", "EXISTS");
    private static final Set<String> ASSERTIONS = Set.of("EXISTS", "EQUALS", "CONTAINS");
    private RedisStepValidator() {}

    static void validate(JsonNode config, String path) {
        if (config == null || !config.isObject()) throw invalid(path, "Redis 步骤配置必须是对象");
        text(config, "dataSourceId", path, "Redis 步骤必须选择数据源");
        String command = config.path("command").asText("").strip().toUpperCase(Locale.ROOT);
        if (!COMMANDS.contains(command)) throw invalid(path + ".command", "Redis 仅支持 GET、SET、DEL、EXISTS");
        String key = config.path("key").asText("");
        if (key.isBlank() || key.length() > 1024) throw invalid(path + ".key", "Redis key 不能为空且不能超过 1024 个字符");
        boolean write = command.equals("SET") || command.equals("DEL");
        if (write && !(config.path("allowWrite").asBoolean(false) && config.path("confirmed").asBoolean(false))) {
            throw invalid(path, "Redis 写操作必须同时开启 allowWrite 并完成 confirmed 二次确认");
        }
        if (command.equals("SET") && !config.has("value")) throw invalid(path + ".value", "SET 必须提供 value");
        if (config.has("extractors") && !config.get("extractors").isArray()) throw invalid(path + ".extractors", "Redis 提取器必须是数组");
        JsonNode assertions = config.get("assertions");
        if (assertions != null && !assertions.isArray()) throw invalid(path + ".assertions", "Redis 断言必须是数组");
        if (assertions != null) for (JsonNode assertion : assertions) {
            String type = assertion.path("type").asText("").strip().toUpperCase(Locale.ROOT);
            if (!ASSERTIONS.contains(type)) throw invalid(path + ".assertions", "Redis 断言类型不支持");
            if (type.equals("EQUALS") || type.equals("CONTAINS")) {
                if (!assertion.has("expected") || assertion.get("expected").isNull()) throw invalid(path + ".assertions", "Redis 值断言必须提供 expected");
            }
        }
    }

    private static void text(JsonNode config, String field, String path, String message) {
        if (config.path(field).asText("").strip().isBlank()) throw invalid(path + "." + field, message);
    }
    private static ApiDomainException invalid(String field, String message) { return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_REDIS_STEP", message, java.util.Map.of("field", field)); }
}
