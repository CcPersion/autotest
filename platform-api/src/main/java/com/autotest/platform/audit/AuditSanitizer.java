package com.autotest.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AuditSanitizer {
    private static final Set<String> SENSITIVE = Set.of("password", "secret", "secretkey", "token",
            "accesstoken", "refreshtoken", "apikey", "authorization", "cookie", "cookies", "setcookie", "xapikey");

    private AuditSanitizer() {
    }

    static JsonNode sanitize(JsonNode input) {
        if (input == null || input.isNull()) return JsonNodeFactory.instance.objectNode();
        JsonNode result = sanitizeNode(input);
        return result.isObject() ? result : JsonNodeFactory.instance.objectNode().set("value", result);
    }

    private static JsonNode sanitizeNode(JsonNode input) {
        if (input.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                result.set(field.getKey(), SENSITIVE.contains(key) ? JsonNodeFactory.instance.textNode("***")
                        : sanitizeNode(field.getValue()));
            }
            return result;
        }
        if (input.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            input.forEach(value -> result.add(sanitizeNode(value)));
            return result;
        }
        if (input.isTextual()) return JsonNodeFactory.instance.textNode(maskText(input.textValue()));
        return input.deepCopy();
    }

    private static String maskText(String value) {
        return value.replaceAll("(?i)(Bearer|Basic)\\s+[^\\s,;]+", "$1 ***")
                .replaceAll("(?i)([?&](?:token|password|secret|api[_-]?key|authorization|cookie)=)[^&#]*", "$1***");
    }
}
