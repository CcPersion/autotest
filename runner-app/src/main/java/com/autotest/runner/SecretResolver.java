package com.autotest.runner;

import java.util.UUID;

/** Runner 从 Platform 获取运行所需密钥的最小契约；实现不得记录返回值。 */
@FunctionalInterface
interface SecretResolver {

    String resolve(UUID projectId, String name) throws Exception;
}
