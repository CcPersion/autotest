package com.autotest.platform.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Set;

public final class WebhookSecurity {
    private static final Set<String> SAFE_FIELDS = Set.of(
            "runId", "projectId", "suiteId", "status", "targetType", "startedAt", "finishedAt",
            "exitCode", "trigger", "errorCode", "message");

    private WebhookSecurity() {
    }

    /** 只复制通知摘要白名单字段，完整请求、响应、日志和密钥永不进入外发正文。 */
    public static ObjectNode sanitizePayload(JsonNode source) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (source == null || !source.isObject()) return result;
        SAFE_FIELDS.forEach(field -> {
            JsonNode value = source.get(field);
            if (value != null && (value.isValueNode())) result.set(field, value.deepCopy());
        });
        return result;
    }

    public static String sign(String secret, String body) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Webhook Secret 不能为空");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256=");
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Webhook 签名失败", exception);
        }
    }
}
