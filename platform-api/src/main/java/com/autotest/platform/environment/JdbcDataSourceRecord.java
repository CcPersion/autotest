package com.autotest.platform.environment;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record JdbcDataSourceRecord(UUID id, UUID projectId, UUID environmentId, String name,
                                   String databaseType, String host, int port, String databaseName,
                                   String username, String secretRef, JsonNode options, int revision,
                                   boolean archived, Instant createdAt, Instant updatedAt) {
}
