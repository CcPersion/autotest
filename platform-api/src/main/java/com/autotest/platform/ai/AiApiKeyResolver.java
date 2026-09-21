package com.autotest.platform.ai;

@FunctionalInterface
public interface AiApiKeyResolver {
    String resolve(String secretRef);
}
