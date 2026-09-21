package com.autotest.platform.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceFixtureContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void referenceManifestContainsTenEvidenceBackedFixturesAndSecurityCases() throws Exception {
        JsonNode root = json.readTree(Files.readString(manifestPath()));
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("pytest-auto-api", root.path("source").path("name").asText());

        JsonNode fixtures = root.path("fixtures");
        assertTrue(fixtures.isArray());
        assertEquals(10, fixtures.size());
        Set<String> expectedCategories = Set.of(
                "GET_QUERY_CACHE", "POST_JSON_ASSERTIONS", "MULTI_DEPENDENCY_JSONPATH", "MULTI_DATA_ROWS",
                "SQL_ASSERTION", "REDIS_READ_WRITE", "FILE_UPLOAD", "DYNAMIC_RANDOM_DATA", "SETUP_CLEANUP",
                "NOTIFICATION_EXPORT");
        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        for (JsonNode fixture : fixtures) {
            String id = fixture.path("id").asText();
            assertTrue(ids.add(id), "duplicate fixture id: " + id);
            assertNotBlank(fixture, "title");
            assertNotBlank(fixture, "category");
            assertTrue(categories.add(fixture.path("category").asText()),
                    "duplicate fixture category: " + fixture.path("category").asText());
            JsonNode source = fixture.path("source");
            assertNotBlank(source, "file");
            assertTrue(source.path("lineStart").asInt() > 0);
            assertNotBlank(source, "anchor");
            JsonNode asset = fixture.path("platformAsset");
            assertNotBlank(asset, "kind");
            assertNotBlank(asset, "operation");
            assertTrue(asset.path("evidenceFields").isArray());
            assertTrue(asset.path("evidenceFields").size() >= 2);
            assertEquals("PASSED", fixture.path("expectedEvidence").path("status").asText());
        }
        assertEquals(expectedCategories, categories);

        JsonNode security = root.path("securityCases");
        assertTrue(security.isArray());
        assertEquals(6, security.size());
        for (JsonNode item : security) {
            assertNotBlank(item, "id");
            assertNotBlank(item, "threat");
            assertTrue(Set.of("REJECT", "REDACT", "ALLOW_WITH_PLACEHOLDER").contains(
                    item.path("expected").asText()));
            assertNotBlank(item, "evidence");
            String state = item.path("verification").path("state").asText();
            assertTrue(Set.of("COVERED", "PENDING").contains(state));
            if ("COVERED".equals(state)) {
                assertNotBlank(item.path("verification"), "test");
            } else {
                assertNotBlank(item.path("verification"), "followUp");
            }
        }

        String content = root.toString();
        assertFalse(content.contains("192.168."));
        assertFalse(content.contains("18721907542"));
        assertFalse(content.contains("192.168.103.205"));
        assertFalse(content.matches(".*(?i)(password|token|secret)[=:][A-Za-z0-9+/]{12,}.*"));
    }

    private static void assertNotBlank(JsonNode parent, String field) {
        assertFalse(parent.path(field).isMissingNode() || parent.path(field).asText().isBlank(),
                "missing field: " + field);
    }

    private static Path manifestPath() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("test-fixtures/pytest-auto-api/manifest.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        try (Stream<Path> ignored = Files.walk(Path.of("."))) {
            return ignored.filter(path -> path.toString().replace('\\', '/').endsWith(
                    "test-fixtures/pytest-auto-api/manifest.json"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("reference fixture manifest not found"));
        }
    }
}
