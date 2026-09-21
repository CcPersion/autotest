package com.autotest.platform.file;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class StorageConfiguration {

    @Bean
    ObjectStorage objectStorage(
            @Value("${autotest.storage.minio.endpoint:}") String endpoint,
            @Value("${autotest.storage.minio.access-key:}") String accessKey,
            @Value("${autotest.storage.minio.secret-key:}") String secretKey,
            @Value("${autotest.storage.minio.bucket:autotest-file-assets}") String bucket,
            @Value("${autotest.storage.minio.local-root:${java.io.tmpdir}/autotest-file-assets}") String localRoot) {
        if (endpoint != null && !endpoint.isBlank()) {
            if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException("MinIO 凭据未配置");
            }
            return new MinioObjectStorage(MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build(), bucket);
        }
        return new LocalObjectStorage(Path.of(localRoot));
    }
}
