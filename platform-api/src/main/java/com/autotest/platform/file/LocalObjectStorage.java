package com.autotest.platform.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class LocalObjectStorage implements ObjectStorage {
    private final Path root;

    LocalObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, InputStream content, long size, String contentType) throws IOException {
        Path target = resolve(objectKey);
        Files.createDirectories(root);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != size) {
            Files.deleteIfExists(target);
            throw new IOException("存储对象大小校验失败");
        }
    }

    @Override
    public InputStream get(String objectKey) throws IOException {
        return Files.newInputStream(resolve(objectKey));
    }

    @Override
    public void delete(String objectKey) throws IOException {
        Files.deleteIfExists(resolve(objectKey));
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.contains("/") || key.contains("\\")) {
            throw new IllegalArgumentException("非法对象键");
        }
        return root.resolve(key).normalize();
    }
}
