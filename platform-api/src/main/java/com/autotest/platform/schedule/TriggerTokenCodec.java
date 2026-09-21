package com.autotest.platform.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class TriggerTokenCodec {
    private static final SecureRandom RANDOM = new SecureRandom();

    private TriggerTokenCodec() {
    }

    public static String issue() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return "aat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("触发令牌不能为空");
        return hex(digest(token));
    }

    public static boolean matches(String token, String expectedHash) {
        if (token == null || expectedHash == null || expectedHash.isBlank()) return false;
        return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }
}
