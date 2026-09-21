package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Downloads only run-snapshot file references into a private per-run directory. */
final class RunFileMaterializer {
    private static final Set<String> SAFE_DOWNLOAD_ERROR_CODES = Set.of(
            "FILE_SNAPSHOT_NOT_FOUND", "FILE_NOT_FOUND", "SNAPSHOT_METADATA_MISMATCH",
            "RUNNER_AUTHENTICATION_REQUIRED");
    private final HttpClient http = HttpClient.newBuilder().build();
    private final ObjectMapper json = new ObjectMapper();
    private final String endpoint;
    private final String token;

    RunFileMaterializer(String platformApiUrl, String callbackToken) {
        if (platformApiUrl == null || platformApiUrl.isBlank() || callbackToken == null || callbackToken.isBlank()) {
            throw new IllegalArgumentException("文件下载配置不能为空");
        }
        endpoint = platformApiUrl.replaceAll("/+$", "");
        token = callbackToken;
    }

    JsonNode materialize(JsonNode input, UUID runId, Path runDirectory) throws Exception {
        JsonNode copy = input == null ? null : input.deepCopy();
        if (!(copy instanceof ObjectNode plan)) return copy;
        Path directory = runDirectory.resolve("request-files").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        JsonNode body = plan.path("body");
        if ("MULTIPART".equals(body.path("type").asText())) {
            JsonNode value = body.path("value");
            JsonNode files = value.isArray() ? value : value.path("files");
            if (files.isArray()) {
                for (JsonNode raw : files) {
                    if (raw instanceof ObjectNode file
                            && ("FILE".equalsIgnoreCase(file.path("kind").asText())
                            || file.hasNonNull("fileId"))) {
                        materializeFile(file, runId, directory, "file-");
                    }
                }
            }
        }
        JsonNode certificate = plan.path("clientCertificate");
        if (!(certificate instanceof ObjectNode cert && cert.path("fileSnapshot").isObject())) {
            certificate = plan.path("options").path("clientCertificate");
        }
        if (certificate instanceof ObjectNode cert && cert.path("fileSnapshot").isObject()) {
            ObjectNode snapshot = (ObjectNode) cert.path("fileSnapshot");
            materializeFile(snapshot, runId, directory, "certificate-");
            cert.put("filePath", snapshot.path("path").asText());
        }
        return copy;
    }

    private void materializeFile(ObjectNode file, UUID runId, Path directory, String prefix) throws Exception {
        String fileId = required(file, "fileId");
        long expectedSize = file.path("size").asLong(-1);
        String expectedSha = required(file, "sha256");
        Path target = directory.resolve(prefix + fileId).normalize();
        if (!target.startsWith(directory)) throw new IllegalArgumentException("DOWNLOAD_PATH_INVALID");
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/internal/runs/"
                        + runId + "/files/" + fileId))
                .header("X-Runner-Token", token).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalArgumentException(downloadFailureCode(response));
        }
        byte[] bytes = response.body();
        if (expectedSize < 0 || bytes.length != expectedSize
                || !MessageDigest.isEqual(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .getBytes(), expectedSha.toLowerCase().getBytes())) {
            throw new IllegalArgumentException("DOWNLOAD_CHECKSUM_MISMATCH");
        }
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        restrict(target);
        file.put("path", target.toString());
    }

    private String downloadFailureCode(HttpResponse<byte[]> response) {
        try {
            JsonNode body = json.readTree(response.body());
            String code = body == null ? "" : body.path("code").asText("");
            if (SAFE_DOWNLOAD_ERROR_CODES.contains(code)) return code;
        } catch (Exception ignored) {
            // Keep the download error fixed and body-free when the response is
            // not the platform's structured error contract.
        }
        return "FILE_DOWNLOAD_FAILED";
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("DOWNLOAD_METADATA_INVALID");
        return value;
    }

    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("DOWNLOAD_PATH_INVALID");
        }
    }
}
