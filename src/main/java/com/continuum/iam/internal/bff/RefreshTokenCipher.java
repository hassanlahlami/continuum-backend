package com.continuum.iam.internal.bff;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM encryption of refresh tokens at rest (key held outside the database). Layout: iv(12) || ciphertext. */
public class RefreshTokenCipher {

    private static final int IV_LEN = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("continuum.auth.bff.encryption-key is required");
        }
        byte[] k = Base64.getDecoder().decode(base64Key.trim());
        if (k.length != 16 && k.length != 24 && k.length != 32) {
            throw new IllegalStateException("continuum.auth.bff.encryption-key must be a 128/192/256-bit AES key");
        }
        this.key = new SecretKeySpec(k, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("refresh token encryption failed", e);
        }
    }

    public String decrypt(byte[] blob) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 0, IV_LEN));
            return new String(c.doFinal(blob, IV_LEN, blob.length - IV_LEN), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("refresh token decryption failed", e);
        }
    }
}
