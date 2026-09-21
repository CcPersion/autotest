package com.autotest.platform.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileAssetPolicyTest {

    @Test
    void acceptsSafeRequestFileAndExposesFrozenLimits() {
        FileAssetPolicy.ValidatedUpload upload = FileAssetPolicy.validateUpload(
                "payload.json", "REQUEST_FILE", "application/json", 12);

        assertEquals("payload.json", upload.originalName());
        assertEquals("REQUEST_FILE", upload.kind());
        assertEquals(12, upload.size());
        assertEquals(10 * 1024 * 1024, FileAssetPolicy.MAX_FILE_BYTES);
        assertEquals(100 * 1024 * 1024, FileAssetPolicy.PROJECT_QUOTA_BYTES);
    }

    @Test
    void rejectsPathLikeNamesAndInvalidFileKindsOrMimes() {
        assertThrows(FileAssetPolicy.UploadRejectedException.class,
                () -> FileAssetPolicy.validateUpload("../payload.json", "REQUEST_FILE", "application/json", 12));
        assertThrows(FileAssetPolicy.UploadRejectedException.class,
                () -> FileAssetPolicy.validateUpload("payload.json", "SCRIPT", "application/json", 12));
        assertThrows(FileAssetPolicy.UploadRejectedException.class,
                () -> FileAssetPolicy.validateUpload("client.p12", "PKCS12", "text/plain", 12));
    }

    @Test
    void rejectsEmptyAndOversizedContent() {
        assertThrows(FileAssetPolicy.UploadRejectedException.class,
                () -> FileAssetPolicy.validateUpload("payload.json", "REQUEST_FILE", "application/json", 0));
        assertThrows(FileAssetPolicy.UploadRejectedException.class,
                () -> FileAssetPolicy.validateUpload("payload.json", "REQUEST_FILE", "application/json",
                        FileAssetPolicy.MAX_FILE_BYTES + 1));
    }

    @Test
    void enforcesPkcs12ExtensionAndAllowedRequestMimeTypes() {
        assertEquals("PKCS12", FileAssetPolicy.validateUpload(
                "client.p12", "PKCS12", "application/x-pkcs12", 12).kind());
        assertThrows(FileAssetPolicy.UploadRejectedException.class,
                () -> FileAssetPolicy.validateUpload("client.pem", "PKCS12", "application/x-pkcs12", 12));
        assertEquals("image/png", FileAssetPolicy.validateUpload(
                "icon.png", "REQUEST_FILE", "image/png", 12).mimeType());
    }
}
