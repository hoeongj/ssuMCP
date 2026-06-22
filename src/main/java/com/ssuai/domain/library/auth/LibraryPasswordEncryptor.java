package com.ssuai.domain.library.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-CBC password encryption matching oasis.ssu.ac.kr's login JavaScript.
 *
 * <p>The PBKDF2 inputs ({@link #KEY_MATERIAL}/{@link #SALT}/{@link #ITERATIONS}/
 * {@link #KEY_BITS}) are all constant, so the derived key is constant too.
 * It is therefore derived once at construction and cached in {@link #secretKey};
 * {@link #encrypt(String)} only runs {@code Cipher.init} + {@code doFinal} per
 * call, avoiding the thousands of PBKDF2 iterations on every login. The IV is
 * fixed (matching the upstream JS), so the ciphertext for a given password is
 * unchanged from the previous per-call-derivation behavior.
 */
@Component
public class LibraryPasswordEncryptor {

    private static final String KEY_MATERIAL = String.join("",
            "M2M2Yjcy",
            "MmU2OTZl",
            "NjU2YjJl",
            "NjM2OTcw",
            "NjU3MjNl");
    private static final String SALT = "kr.inek.encrypte";
    private static final String IV = "[kr:inek:solved]";
    private static final int ITERATIONS = 5000;
    private static final int KEY_BITS = 128;

    private final SecretKeySpec secretKey;

    public LibraryPasswordEncryptor() {
        this.secretKey = deriveSecretKey();
    }

    public String encrypt(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("rawPassword is required");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder()
                    .encodeToString(cipher.doFinal(rawPassword.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt library password", exception);
        }
    }

    private static SecretKeySpec deriveSecretKey() {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    KEY_MATERIAL.toCharArray(),
                    SALT.getBytes(StandardCharsets.UTF_8),
                    ITERATIONS,
                    KEY_BITS);
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec)
                    .getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to derive library password encryption key", exception);
        }
    }
}
