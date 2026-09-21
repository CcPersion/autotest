package com.autotest.platform.run;

import com.autotest.contracts.network.TargetAllowlist;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 将项目级目标规则固化到运行计划，并在平台侧先做一次目标校验。 */
final class TargetPlanPolicy {

    private TargetPlanPolicy() {
    }

    static JsonNode attachAndValidate(JsonNode source, List<String> rules, ObjectMapper json) {
        TargetAllowlist allowlist;
        try {
            allowlist = TargetAllowlist.parse(rules);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage() == null ? "目标白名单不合法" : exception.getMessage());
        }
        JsonNode copy = source.deepCopy();
        if (!(copy instanceof ObjectNode root)) {
            throw invalid("执行计划必须是 JSON 对象");
        }
        ArrayNode serialized = json.createArrayNode();
        allowlist.rules().forEach(serialized::add);
        root.set("targetAllowlist", serialized);
        ObjectNode snapshot = json.createObjectNode();
        snapshot.set("rules", serialized.deepCopy());
        root.set("targetPolicySnapshot", snapshot);
        root.put("targetPolicyRequired", true);
        root.put("targetDnsRequired", true);
        validateTargets(copy, allowlist);
        return copy;
    }

    private static void validateTargets(JsonNode node, TargetAllowlist allowlist) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                if ((name.equalsIgnoreCase("baseUrl") || name.equalsIgnoreCase("urlTemplate"))
                        && value.isTextual()) {
                    String text = value.textValue().strip();
                    String lower = text.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("http://") || lower.startsWith("https://")) {
                        check(allowlist, text);
                    }
                }
                validateTargets(value, allowlist);
            }
        } else if (node.isArray()) {
            node.forEach(value -> validateTargets(value, allowlist));
        }
    }

    private static void check(TargetAllowlist allowlist, String value) {
        try {
            TargetAllowlist.Decision decision = allowlist.evaluate(new URI(value));
            if (!decision.allowed()) {
                throw invalid("HTTP 目标不在项目白名单中: " + decision.host());
            }
        } catch (URISyntaxException exception) {
            throw invalid("HTTP 目标 URL 不合法");
        }
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "TARGET_NOT_ALLOWED", message);
    }
}
