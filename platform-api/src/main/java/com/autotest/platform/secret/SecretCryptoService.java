package com.autotest.platform.secret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class SecretCryptoService {

    private static final int KEY_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCryptoService(@Value("${AUTOTEST_MASTER_KEY:}") String encodedKey) {
        try {
            if (encodedKey == null || encodedKey.isBlank()) {
                throw new IllegalArgumentException();
            }
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != KEY_LENGTH) {
                throw new IllegalArgumentException();
            }
            this.key = new SecretKeySpec(decoded, "AES");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AUTOTEST_MASTER_KEY is invalid");
        }
    }

    public EncryptedValue encrypt(UUID projectId, UUID secretId, String value) {
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, projectId, secretId, nonce);
            return new EncryptedValue(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (GeneralSecurityException exception) {
            throw new SecretCryptoException();
        }
    }

    public byte[] decrypt(UUID projectId, UUID secretId, byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, projectId, secretId, nonce);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new SecretCryptoException();
        }
    }

    private Cipher cipher(int mode, UUID projectId, UUID secretId, byte[] nonce) throws GeneralSecurityException {
        if (nonce == null || nonce.length != NONCE_LENGTH) {
            throw new GeneralSecurityException();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        cipher.updateAAD(("autotest-secret:v1:" + projectId + ":" + secretId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    public record EncryptedValue(byte[] ciphertext, byte[] nonce) {
    }
}
