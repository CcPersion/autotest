package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将密钥引用转换为受控 JMeter 文件函数，避免把明文写进 JMX、JTL 或命令行。
 * 文件仅存在于当前运行目录，关闭时立即删除。
 */
final class SecretFileMaterializer implements AutoCloseable {

    private static final Pattern SECRET_REFERENCE = Pattern.compile("\\$\\{secret:([A-Za-z0-9][A-Za-z0-9._-]*)}");
    private static final String FILE_FUNCTION_PREFIX = "${__autotestSecret(";
    private static final String FILE_FUNCTION_SUFFIX = ")}";

    private final Path directory;
    private final UUID projectId;
    private final SecretResolver resolver;
    private final Map<String, Path> files = new HashMap<>();

    SecretFileMaterializer(Path runDirectory, UUID projectId, SecretResolver resolver) {
        if (runDirectory == null || projectId == null || resolver == null) {
            throw new IllegalArgumentException("密钥物化参数不能为空");
        }
        this.directory = runDirectory.resolve("secret-files").normalize();
        if (!this.directory.startsWith(runDirectory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("密钥文件目录越界");
        }
        this.projectId = projectId;
        this.resolver = resolver;
    }

    JsonNode materialize(JsonNode input) {
        try {
            return copyAndMaterialize(input);
        } catch (IOException exception) {
            close();
            throw new IllegalArgumentException("无法准备运行密钥", exception);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /** 将 Base64 编码的 PKCS12 密钥材料写入本次运行的受限临时文件。 */
    Path materializePkcs12(String reference) {
        String name = secretName(reference);
        String cacheKey = "pkcs12:" + name;
        return files.computeIfAbsent(cacheKey, ignored -> {
            String value = resolveSecret(name);
            byte[] content;
            try {
                content = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("PKCS12 密钥必须是合法 Base64", exception);
            }
            if (content.length == 0 || content.length > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("PKCS12 密钥大小不合法");
            }
            return createBinaryFile(content, ".p12");
        });
    }

    /** 只在内存中解析密钥引用，调用方不得把返回值写入计划或日志。 */
    String resolveText(String reference) {
        return resolveSecret(secretName(reference));
    }

    private JsonNode copyAndMaterialize(JsonNode input) throws IOException {
        if (input == null || input.isNull()) {
            return input;
        }
        if (input.isTextual()) {
            return TextNode.valueOf(replace(input.textValue()));
        }
        if (input.isObject()) {
            var output = JsonNodeFactory.instance.objectNode();
            input.fields().forEachRemaining(entry -> {
                try {
                    output.set(entry.getKey(), copyAndMaterialize(entry.getValue()));
                } catch (IOException exception) {
                    throw new MaterializationIOException(exception);
                }
            });
            return output;
        }
        if (input.isArray()) {
            var output = JsonNodeFactory.instance.arrayNode();
            input.forEach(item -> {
                try {
                    output.add(copyAndMaterialize(item));
                } catch (IOException exception) {
                    throw new MaterializationIOException(exception);
                }
            });
            return output;
        }
        return input.deepCopy();
    }

    private String replace(String text) {
        Matcher matcher = SECRET_REFERENCE.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Path file = files.computeIfAbsent(name, ignored -> createSecretFile(name));
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(FILE_FUNCTION_PREFIX + file.toString() + FILE_FUNCTION_SUFFIX));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private Path createSecretFile(String name) {
        try {
            Files.createDirectories(directory);
            restrict(directory, true);
            String value = resolveSecret(name);
            Path file = directory.resolve("secret-" + UUID.randomUUID() + ".txt").normalize();
            if (!file.startsWith(directory)) {
                throw new IllegalArgumentException("密钥文件路径越界");
            }
            createRestrictedFile(file);
            Files.writeString(file, value, StandardCharsets.UTF_8);
            restrict(file, false);
            return file;
        } catch (IOException exception) {
            throw new MaterializationIOException(exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解析运行密钥", exception);
        }
    }

    private Path createBinaryFile(byte[] content, String suffix) {
        try {
            Files.createDirectories(directory);
            restrict(directory, true);
            Path file = directory.resolve("secret-" + UUID.randomUUID() + suffix).normalize();
            if (!file.startsWith(directory)) {
                throw new IllegalArgumentException("密钥文件路径越界");
            }
            createRestrictedFile(file);
            Files.write(file, content);
            restrict(file, false);
            return file;
        } catch (IOException exception) {
            throw new MaterializationIOException(exception);
        }
    }

    private String resolveSecret(String name) {
        try {
            String value = resolver.resolve(projectId, name);
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("运行密钥为空");
            }
            return value;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解析运行密钥", exception);
        }
    }

    private static String secretName(String reference) {
        Matcher matcher = SECRET_REFERENCE.matcher(reference == null ? "" : reference);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("密钥引用不合法");
        }
        return matcher.group(1);
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        try {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 测试文件系统没有 POSIX 权限模型；Runner 生产镜像运行在 Linux。
        }
    }

    private static void createRestrictedFile(Path file) throws IOException {
        try {
            var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.createFile(file, PosixFilePermissions.asFileAttribute(permissions));
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(file);
        }
    }

    @Override
    public void close() {
        files.values().forEach(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 不把清理异常写入日志；调用方仍会把主运行结果落库。
            }
        });
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // 目录可能仍包含 JMeter 打开的文件，下一次启动由运行目录清理策略处理。
        }
        files.clear();
    }

    private static final class MaterializationIOException extends RuntimeException {
        private MaterializationIOException(IOException cause) {
            super(cause);
        }
    }
}
