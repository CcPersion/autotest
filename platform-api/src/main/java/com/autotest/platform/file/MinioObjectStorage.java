package com.autotest.platform.file;

import io.minio.GetObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

import java.io.IOException;
import java.io.InputStream;

final class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    MinioObjectStorage(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO bucket 初始化失败", exception);
        }
    }

    @Override
    public void put(String objectKey, InputStream content, long size, String contentType) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(content, size, -1).contentType(contentType).build());
        } catch (Exception exception) {
            throw new IOException("对象存储写入失败", exception);
        }
    }

    @Override
    public InputStream get(String objectKey) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IOException("对象存储读取失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new IOException("对象存储删除失败", exception);
        }
    }
}
