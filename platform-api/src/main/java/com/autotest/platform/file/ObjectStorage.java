package com.autotest.platform.file;

import java.io.IOException;
import java.io.InputStream;

interface ObjectStorage {
    void put(String objectKey, InputStream content, long size, String contentType) throws IOException;

    InputStream get(String objectKey) throws IOException;

    void delete(String objectKey) throws IOException;
}
