package com.autotest.platform.ai;

import org.springframework.stereotype.Component;

/** 默认只读取运行环境注入的临时凭据，不把凭据放入配置、日志或响应。 */
@Component
public class EnvironmentAiApiKeyResolver implements AiApiKeyResolver {
    @Override
    public String resolve(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return null;
        }
        return System.getenv("AUTOTEST_AI_API_KEY");
    }
}
