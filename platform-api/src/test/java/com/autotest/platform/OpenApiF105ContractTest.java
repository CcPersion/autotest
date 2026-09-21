package com.autotest.platform;

import com.autotest.platform.api.ApiCaseController;
import com.autotest.platform.api.ApiDefinitionController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiF105ContractTest {

    private static final Set<String> ERROR_RESPONSES = Set.of("400", "401", "403", "404", "409");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace");
    private static final Class<?>[] F105_CONTROLLERS = {ApiDefinitionController.class, ApiCaseController.class};
    private static final Map<String, Set<String>> CONTROLLER_ROUTES = controllerRoutesFromAnnotations(F105_CONTROLLERS);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void openApiDocumentMatchesF105ControllerRouteContract() throws Exception {
        JsonNode document = readContract();

        assertEquals("3.1.0", document.path("openapi").asText());
        JsonNode paths = document.path("paths");
        assertTrue(paths.isObject());

        Map<String, Set<String>> documentedRoutes = routeMethods(paths);
        assertEquals(CONTROLLER_ROUTES, documentedRoutes);
        assertEquals(CONTROLLER_ROUTES.keySet(), documentedRoutes.keySet());
        for (Map.Entry<String, Set<String>> route : CONTROLLER_ROUTES.entrySet()) {
            assertEquals(route.getValue(), documentedRoutes.get(route.getKey()));
        }
    }

    @Test
    void operationsUseRefsRequiredDtosEnumsSecurityAndUnifiedErrors() throws Exception {
        JsonNode document = readContract();
        JsonNode paths = document.path("paths");
        JsonNode components = document.path("components");
        JsonNode schemas = components.path("schemas");
        JsonNode securitySchemes = components.path("securitySchemes");

        assertTrue(securitySchemes.has("sessionCookie"));
        assertTrue(securitySchemes.has("csrfToken"));
        assertEquals("apiKey", securitySchemes.path("sessionCookie").path("type").asText());
        assertEquals("cookie", securitySchemes.path("sessionCookie").path("in").asText());
        assertEquals("JSESSIONID", securitySchemes.path("sessionCookie").path("name").asText());
        assertEquals("apiKey", securitySchemes.path("csrfToken").path("type").asText());
        assertEquals("header", securitySchemes.path("csrfToken").path("in").asText());
        assertEquals("X-XSRF-TOKEN", securitySchemes.path("csrfToken").path("name").asText());

        assertRequired(schemas, "DefinitionResponse", "id", "projectId", "moduleId", "name", "method",
                "urlTemplate", "requestSpec", "revision", "archived", "createdAt", "updatedAt");
        assertRequired(schemas, "CaseResponse", "id", "projectId", "apiDefinitionId", "name", "caseSpec",
                "variables", "assertions", "revision", "archived", "createdAt", "updatedAt");
        assertRequired(schemas, "DefinitionWrite", "name", "method", "urlTemplate", "requestSpec");
        assertRequired(schemas, "DefinitionUpdate", "name", "method", "urlTemplate", "requestSpec", "revision");
        assertRequired(schemas, "CaseWrite", "name", "caseSpec", "variables", "assertions");
        assertRequired(schemas, "CaseUpdate", "name", "caseSpec", "variables", "assertions", "revision");
        assertRequired(schemas, "RevisionRequest", "revision");
        assertRequired(schemas, "ErrorResponse", "code", "message", "details", "traceId");
        assertObjectOrNull(schemas.path("ErrorResponse").path("properties").path("details"));

        assertEnum(schemas, "HttpMethod", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
        assertEnum(schemas, "RequestBodyType", "NONE", "JSON", "TEXT", "URLENCODED", "MULTIPART");
        assertEnum(schemas, "AssertionType", "STATUS", "JSON_PATH", "JMES_PATH", "XPATH",
                "BODY", "HEADER", "COOKIE", "SCHEMA", "RESPONSE_TIME");
        assertEnum(schemas, "JsonPathOperator", "EXISTS", "NOT_EXISTS", "EQUALS", "NOT_EQUALS",
                "CONTAINS", "NOT_CONTAINS");
        assertEnum(schemas, "StatusOperator", "EQUALS");
        assertEnum(schemas, "ResponseOperator", "EQUALS", "CONTAINS", "NOT_CONTAINS", "MATCHES");
        assertEnum(schemas, "SchemaOperator", "VALIDATE");
        assertEnum(schemas, "ResponseTimeOperator", "LESS_THAN");

        for (Map.Entry<String, Set<String>> route : CONTROLLER_ROUTES.entrySet()) {
            String documentedPath = findDocumentedPath(paths, route.getKey());
            JsonNode pathItem = paths.path(documentedPath);
            for (String method : route.getValue()) {
                JsonNode operation = pathItem.path(method);
                assertFalse(operation.isMissingNode(), () -> route.getKey() + " missing " + method);
                assertTrue(operation.path("security").isArray() && operation.path("security").size() > 0,
                        () -> route.getKey() + " " + method + " has no security");
                assertPathParametersRequired(operation, documentedPath, components.path("parameters"));
                assertUnifiedErrors(operation, route.getKey() + " " + method);
                assertSuccessResponses(operation, route.getKey(), method);
                assertRequestRefs(operation, route.getKey(), method);
            }
        }

        JsonNode requestSpec = schemas.path("RequestSpec");
        assertRequired(schemas, "RequestSpec", "pathParams", "query", "headers", "body");
        assertEquals("false", requestSpec.path("additionalProperties").asText());
        assertRequired(schemas, "CaseSpec", "pathParams", "query", "headers", "body");
        assertFalse(schemas.path("JsonPathExistsAssertion").path("required").toString().contains("expected"));
        for (String writeSchema : List.of("DefinitionWrite", "DefinitionUpdate", "CaseWrite", "CaseUpdate")) {
            assertEquals("false", schemas.path(writeSchema).path("additionalProperties").asText(),
                    writeSchema + " must reject unknown top-level fields");
        }
    }

    @Test
    void reflectionRouteContractDetectsAllMethodsAndExactPathVariables() {
        Map<String, Set<String>> fixtureRoutes = controllerRoutesFromAnnotations(MappingFixture.class);

        assertEquals(Set.of("patch"), fixtureRoutes.get("/fixture/{actualId}/patch"));
        assertEquals(Set.of("delete"), fixtureRoutes.get("/fixture/{actualId}/delete"));
        assertFalse(fixtureRoutes.containsKey("/fixture/{wrongId}/patch"));

        Map<String, Set<String>> renamedVariable = Map.of(
                "/fixture/{wrongId}/patch", Set.of("patch"),
                "/fixture/{actualId}/delete", Set.of("delete"));
        assertThrows(AssertionError.class, () -> assertEquals(fixtureRoutes, renamedVariable),
                "路径变量名变化必须使合同比较失败");

        Map<String, Set<String>> missingPatch = Map.of(
                "/fixture/{actualId}/delete", Set.of("delete"));
        assertThrows(AssertionError.class, () -> assertEquals(fixtureRoutes, missingPatch),
                "Controller 新增 PATCH 端点必须使合同比较失败");
    }

    private JsonNode readContract() throws IOException {
        Path direct = Path.of("docs", "api", "f1-05-openapi.json");
        Path parent = Path.of("..", "docs", "api", "f1-05-openapi.json");
        Path path = Files.exists(direct) ? direct : parent;
        assertTrue(Files.exists(path), "F1-05 OpenAPI contract file is missing: " + direct.toAbsolutePath());
        return json.readTree(Files.readString(path));
    }

    private Map<String, Set<String>> routeMethods(JsonNode paths) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        paths.fieldNames().forEachRemaining(path -> {
            Set<String> methods = new LinkedHashSet<>();
            paths.path(path).fieldNames().forEachRemaining(method -> {
                if (HTTP_METHODS.contains(method)) methods.add(method);
            });
            result.put(normalizePath(path), methods);
        });
        return result;
    }

    private String findDocumentedPath(JsonNode paths, String normalizedPath) {
        var names = paths.fieldNames();
        while (names.hasNext()) {
            String path = names.next();
            if (normalizedPath.equals(normalizePath(path))) return path;
        }
        return normalizedPath;
    }

    private void assertPathParametersRequired(JsonNode operation, String path, JsonNode parameterComponents) {
        for (String variable : path.substring(1).split("/")) {
            if (!variable.startsWith("{")) continue;
            String name = variable.substring(1, variable.length() - 1);
            JsonNode parameter = findParameter(operation, name, parameterComponents);
            assertNotNull(parameter, () -> path + " missing path parameter " + name);
            assertEquals("path", parameter.path("in").asText());
            assertTrue(parameter.path("required").asBoolean(), () -> path + " path parameter is optional: " + name);
        }
    }

    private JsonNode findParameter(JsonNode operation, String name, JsonNode parameterComponents) {
        for (JsonNode parameter : operation.path("parameters")) {
            JsonNode resolved = parameter;
            String ref = parameter.path("$ref").asText();
            if (!ref.isBlank() && ref.startsWith("#/components/parameters/")) {
                resolved = parameterComponents.path(ref.substring("#/components/parameters/".length()));
            }
            if (name.equals(resolved.path("name").asText())) return resolved;
        }
        return null;
    }

    private void assertUnifiedErrors(JsonNode operation, String operationName) {
        for (String status : ERROR_RESPONSES) {
            JsonNode response = operation.path("responses").path(status);
            assertFalse(response.isMissingNode(), () -> operationName + " missing error response " + status);
            assertRef(response, "#/components/responses/" + responseName(status), operationName + " " + status);
        }
    }

    private void assertSuccessResponses(JsonNode operation, String path, String method) {
        JsonNode responses = operation.path("responses");
        boolean createsResource = "post".equals(method)
                && (path.endsWith("api-definitions") || path.endsWith("/cases"));
        if (createsResource) {
            assertFalse(responses.path("201").isMissingNode(), () -> path + " " + method + " missing 201");
        } else {
            assertFalse(responses.path("200").isMissingNode(), () -> path + " " + method + " missing 200");
        }
    }

    private void assertRequestRefs(JsonNode operation, String path, String method) {
        if (Set.of("post", "put").contains(method)) {
            JsonNode requestBody = operation.path("requestBody");
            assertFalse(requestBody.isMissingNode(), () -> path + " " + method + " missing requestBody");
            String expected = path.endsWith("/archive") ? "RevisionRequest"
                    : path.contains("/cases") ? ("put".equals(method) ? "CaseUpdate" : "CaseWrite")
                    : ("put".equals(method) ? "DefinitionUpdate" : "DefinitionWrite");
            assertRef(requestBody.path("content").path("application/json").path("schema"),
                    "#/components/schemas/" + expected, path + " " + method + " request");
        }
    }

    private void assertRequired(JsonNode schemas, String name, String... fields) {
        JsonNode schema = schemas.path(name);
        assertFalse(schema.isMissingNode(), "Missing schema " + name);
        for (String field : fields) assertTrue(schema.path("required").toString().contains("\"" + field + "\""),
                () -> name + " missing required field " + field);
    }

    private void assertEnum(JsonNode schemas, String name, String... values) {
        JsonNode schema = schemas.path(name);
        assertFalse(schema.isMissingNode(), "Missing enum schema " + name);
        Set<String> actual = new LinkedHashSet<>();
        schema.path("enum").forEach(value -> actual.add(value.asText()));
        assertEquals(Set.of(values), actual, "Enum mismatch for " + name);
    }

    private void assertObjectOrNull(JsonNode schema) {
        assertTrue(schema.isObject(), "ErrorResponse.details schema is missing");
        JsonNode variants = schema.path("oneOf");
        assertTrue(variants.isArray(), "ErrorResponse.details must allow object or null");
        boolean hasErrorDetailsObject = false;
        boolean hasNull = false;
        for (JsonNode variant : variants) {
            if ("#/components/schemas/ErrorDetails".equals(variant.path("$ref").asText())) {
                hasErrorDetailsObject = true;
            }
            if ("null".equals(variant.path("type").asText())) {
                hasNull = true;
            }
        }
        assertTrue(hasErrorDetailsObject, "ErrorResponse.details must allow an object");
        assertTrue(hasNull, "ErrorResponse.details must allow null");
    }

    private void assertRef(JsonNode node, String expected, String context) {
        assertEquals(expected, node.path("$ref").asText(), context + " must use " + expected);
    }

    private String responseName(String status) {
        return switch (status) {
            case "400" -> "BadRequest";
            case "401" -> "Unauthorized";
            case "403" -> "Forbidden";
            case "404" -> "NotFound";
            case "409" -> "Conflict";
            default -> throw new IllegalArgumentException(status);
        };
    }

    private static Map<String, Set<String>> controllerRoutesFromAnnotations(Class<?>... controllers) {
        Map<String, Set<String>> routes = new LinkedHashMap<>();
        for (Class<?> controller : controllers) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertNotNull(classMapping, () -> controller.getName() + " is missing class-level @RequestMapping");
            for (String prefix : mappingPaths(classMapping.path(), classMapping.value())) {
                for (Method method : controller.getDeclaredMethods()) {
                    addMethodRoutes(routes, prefix, classMapping, method);
                }
            }
        }
        return routes;
    }

    private static void addMethodRoutes(Map<String, Set<String>> routes, String prefix,
                                        RequestMapping classMapping, Method method) {
        RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) return;
        for (RequestMethod requestMethod : effectiveMethods(classMapping, methodMapping)) {
            addRoutes(routes, prefix, requestMethod.name().toLowerCase(Locale.ROOT),
                    methodMapping.path(), methodMapping.value());
        }
    }

    private static Set<RequestMethod> effectiveMethods(RequestMapping classMapping, RequestMapping methodMapping) {
        Set<RequestMethod> methods = methodMapping.method().length == 0
                ? EnumSet.allOf(RequestMethod.class)
                : EnumSet.copyOf(Arrays.asList(methodMapping.method()));
        if (classMapping.method().length > 0) {
            methods.retainAll(Arrays.asList(classMapping.method()));
        }
        return methods;
    }

    private static void addRoutes(Map<String, Set<String>> routes, String prefix, String method,
                                  String[] paths, String[] values) {
        for (String suffix : mappingPaths(paths, values)) {
            routes.computeIfAbsent(normalizePath(prefix + suffix), ignored -> new LinkedHashSet<>()).add(method);
        }
    }

    private static List<String> mappingPaths(String[] paths, String[] values) {
        String[] selected = paths.length == 0 ? values : paths;
        return selected.length == 0 ? List.of("") : Arrays.asList(selected);
    }

    private static String normalizePath(String path) {
        String normalized = path.replaceAll("/+", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (normalized.length() > 1 && normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    @RequestMapping("/fixture/{actualId}")
    static final class MappingFixture {
        @RequestMapping(path = "/patch", method = RequestMethod.PATCH)
        void patch() {
        }

        @RequestMapping(path = "/delete", method = RequestMethod.DELETE)
        void delete() {
        }
    }
}
