package com.autotest.platform.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ImportParserContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<String> twentyCurls() {
        return Stream.of(
                "curl https://example.test/health",
                "curl -X GET https://example.test/users",
                "curl --request POST https://example.test/users -H 'Content-Type: application/json' -d '{\"name\":\"Ada\"}'",
                "curl -X PUT 'https://example.test/users/7?verbose=true' --data-raw '{\"name\":\"Ada\"}'",
                "curl -X PATCH https://example.test/users/7 -H 'Content-Type: application/json' --data '{\"active\":true}'",
                "curl -X DELETE https://example.test/users/7",
                "curl -H 'Accept: application/json' https://example.test/orders?state=open",
                "curl --url https://example.test/search?q=hello%20world",
                "curl -H 'X-Trace: abc' https://example.test/trace",
                "curl -H 'Content-Type: text/plain' --data 'hello world' https://example.test/text",
                "curl -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'name=Ada' https://example.test/form",
                "curl -b 'session=abc' https://example.test/profile",
                "curl --cookie 'theme=dark; lang=zh' https://example.test/preferences",
                "curl -G --data-urlencode 'q=hello world' https://example.test/search",
                "curl -X OPTIONS https://example.test/options",
                "curl -X HEAD https://example.test/head",
                "curl --request POST --header 'X-Env: test' --data-binary '{\"ok\":true}' https://example.test/check",
                "curl -H \"Accept: */*\" https://example.test/wildcard",
                "curl 'https://example.test/path%20space'",
                "curl -X POST https://example.test/nested -H 'Content-Type: application/json' -d '{\"items\":[1,2]}'"
        );
    }

    @ParameterizedTest
    @MethodSource("twentyCurls")
    void parsesTwentyRepresentativeCurlInputs(String source) {
        ImportDocument document = new CurlImportParser(mapper).parse(source);

        assertEquals(1, document.candidates().size());
        ImportCandidate candidate = document.candidates().get(0);
        assertFalse(candidate.method().isBlank());
        assertFalse(candidate.urlTemplate().isBlank());
        assertNotNull(candidate.caseSpec());
        assertNotNull(candidate.requestSpec());
    }

    @Test
    void rejectsShellExpansionAndReportsLocationWithoutEchoingPayload() {
        String secret = "top-secret-value";
        ImportParseException exception = assertThrows(ImportParseException.class,
                () -> new CurlImportParser(mapper).parse("curl https://example.test/a?x=$(cat /tmp/key) " + secret));

        assertTrue(exception.path().contains("curl"));
        assertFalse(exception.getMessage().contains(secret));
    }

    @Test
    void masksSensitiveCurlHeadersAsWarningsAndNeverPersistsValue() {
        ImportCandidate candidate = new CurlImportParser(mapper).parse(
                "curl https://example.test/me -H 'Authorization: Bearer top-secret-value'")
                .candidates().get(0);

        assertTrue(candidate.warnings().stream().anyMatch(item -> item.contains("Authorization")));
        assertFalse(candidate.requestSpec().toString().contains("top-secret-value"));
        assertTrue(candidate.requestSpec().toString().contains("${secret:"));
    }

    @Test
    void rejectsCurlFileArguments() {
        ImportParseException exception = assertThrows(ImportParseException.class,
                () -> new CurlImportParser(mapper).parse("curl -d @payload.json https://example.test/upload"));
        assertEquals("curl.data", exception.path());
    }

    @Test
    void parsesJsonOpenApiIntoDefinitionAndDefaultCase() {
        String source = """
                {"openapi":"3.0.3","info":{"title":"Orders","version":"1"},
                 "servers":[{"url":"https://api.example.test/v1"}],
                 "paths":{"/orders/{id}":{"get":{"operationId":"getOrder","parameters":[
                   {"name":"id","in":"path","required":true,"example":"42"},
                   {"name":"verbose","in":"query","example":"true"}],
                   "responses":{"200":{"description":"ok"}}}}}}
                """;

        ImportDocument document = new OpenApiImportParser(mapper).parse(source);
        ImportCandidate candidate = document.candidates().get(0);

        assertEquals("GET", candidate.method());
        assertEquals("https://api.example.test/v1/orders/{id}", candidate.urlTemplate());
        assertEquals("getOrder", candidate.definitionName());
        assertEquals("42", candidate.requestSpec().path("pathParams").get(0).path("value").asText());
    }

    @Test
    void parsesYamlOpenApiBatchAndRejectsRemoteRefs() {
        String yaml = """
                openapi: 3.0.3
                info:
                  title: Demo
                  version: '1'
                paths:
                  /ping:
                    post:
                      responses:
                        '201':
                          description: created
                  /health:
                    get:
                      responses:
                        '200':
                          description: ok
                """;

        assertEquals(2, new OpenApiImportParser(mapper).parse(yaml).candidates().size());
        ImportParseException exception = assertThrows(ImportParseException.class,
                () -> new OpenApiImportParser(mapper).parse("{\"openapi\":\"3.0.3\",\"paths\":{\"/x\":{\"get\":{\"$ref\":\"https://evil.test/api.yaml\"}}}}"));
        assertTrue(exception.path().contains("$ref"));
    }

    @Test
    void parsesOpenApiJsonBodyAndUrlEncodedSchemaAsThirdFixture() {
        String source = """
                {"openapi":"3.0.3","info":{"title":"Users","version":"1"},
                 "paths":{"/users":{"post":{"operationId":"createUser",
                   "requestBody":{"content":{"application/json":{"example":{"name":"Ada"}}}},
                   "responses":{"201":{"description":"created"}}}},
                 "/search":{"post":{"requestBody":{"content":{"application/x-www-form-urlencoded":
                   {"schema":{"type":"object","properties":{"q":{"type":"string"}}}}}},
                   "responses":{"200":{"description":"ok"}}}}}}
                """;

        ImportDocument document = new OpenApiImportParser(mapper).parse(source);

        assertEquals(2, document.candidates().size());
        assertEquals("JSON", document.candidates().get(0).requestSpec().path("body").path("type").asText());
        assertEquals("URLENCODED", document.candidates().get(1).requestSpec().path("body").path("type").asText());
    }
}
