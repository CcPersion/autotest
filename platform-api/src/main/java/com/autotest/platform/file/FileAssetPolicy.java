package com.autotest.platform.file;

import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/**
 * Frozen file upload policy for F2-01.  This class deliberately only validates
 * client supplied metadata; object names and storage locations are generated
 * by the service and never accepted from the request.
 */
public final class FileAssetPolicy {

    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final int MAX_FILES_PER_REQUEST = 10;
    public static final long PROJECT_QUOTA_BYTES = 100L * 1024 * 1024;

    private static final Set<String> REQUEST_MIMES = Set.of(
            "application/json", "application/octet-stream", "text/plain",
            "text/csv", "application/xml", "application/pdf", "image/png", "image/jpeg");
    private static final String PKCS12_MIME = "application/x-pkcs12";

    private FileAssetPolicy() {
    }

    public static ValidatedUpload validateUpload(String originalName, String kind,
                                                  String contentType, long size) {
        if (originalName == null || originalName.isBlank()
                || originalName.codePointCount(0, originalName.length()) > 128
                || originalName.getBytes(StandardCharsets.UTF_8).length > 255
                || originalName.equals(".") || originalName.equals("..")
                || originalName.contains("/") || originalName.contains("\\")
                || originalName.contains(":")
                || originalName.indexOf('\u0000') >= 0
                || originalName.chars().anyMatch(Character::isISOControl)) {
            throw new UploadRejectedException("FILE_NAME_INVALID");
        }
        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("REQUEST_FILE", "PKCS12").contains(normalizedKind)) {
            throw new UploadRejectedException("MIME_UNSUPPORTED");
        }
        if (size <= 0) {
            throw new UploadRejectedException("FILE_EMPTY");
        }
        if (size > MAX_FILE_BYTES) {
            throw new UploadRejectedException("FILE_LIMIT_EXCEEDED");
        }
        String normalizedMime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = normalizedMime.indexOf(';');
        if (semicolon >= 0) {
            normalizedMime = normalizedMime.substring(0, semicolon).trim();
        }
        if ("PKCS12".equals(normalizedKind)
                && (!PKCS12_MIME.equals(normalizedMime)
                || !(normalizedName(originalName).endsWith(".p12") || normalizedName(originalName).endsWith(".pfx")))) {
            throw new UploadRejectedException("MIME_UNSUPPORTED");
        }
        if (!"PKCS12".equals(normalizedKind) && !REQUEST_MIMES.contains(normalizedMime)) {
            throw new UploadRejectedException("MIME_UNSUPPORTED");
        }
        return new ValidatedUpload(originalName, normalizedKind, normalizedMime, size);
    }

    private static String normalizedName(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public record ValidatedUpload(String originalName, String kind, String mimeType, long size) {
    }

    public static final class UploadRejectedException extends IllegalArgumentException {
        public UploadRejectedException(String code) {
            super(code);
        }
    }
}
