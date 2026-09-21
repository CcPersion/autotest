package com.autotest.platform.suite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestSuiteResponse(UUID id, UUID projectId, String name, String description, UUID environmentId,
                                int revision, boolean archived, Instant createdAt, Instant updatedAt,
                                List<MemberResponse> members) {
    public record MemberResponse(UUID id, int position, String targetType, UUID targetId, boolean enabled) {
        static MemberResponse from(TestSuiteMemberRecord member) {
            return new MemberResponse(member.id(), member.position(), member.targetType(), member.targetId(), member.enabled());
        }
    }

    static TestSuiteResponse from(TestSuiteRecord suite) {
        return new TestSuiteResponse(suite.id(), suite.projectId(), suite.name(), suite.description(), suite.environmentId(),
                suite.revision(), suite.archived(), suite.createdAt(), suite.updatedAt(),
                suite.members().stream().map(MemberResponse::from).toList());
    }
}
