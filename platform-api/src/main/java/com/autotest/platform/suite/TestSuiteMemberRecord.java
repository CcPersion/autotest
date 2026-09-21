package com.autotest.platform.suite;

import java.util.UUID;

public record TestSuiteMemberRecord(UUID id, UUID suiteId, UUID projectId, int position,
                                    String targetType, UUID targetId, boolean enabled) {
}
