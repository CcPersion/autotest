package com.autotest.platform.environment;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record RedisDataSourceRecord(UUID id, UUID projectId, UUID environmentId, String name,
                                    String host, int port, int databaseNumber, String username,
                                    String secretRef, JsonNode options, int revision, boolean archived,
                                    Instant createdAt, Instant updatedAt) {
}
