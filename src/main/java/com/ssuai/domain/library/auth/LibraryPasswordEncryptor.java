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

    public String encrypt(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("rawPassword is required");
        }
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    KEY_MATERIAL.toCharArray(),
                    SALT.getBytes(StandardCharsets.UTF_8),
                    ITERATIONS,
                    KEY_BITS);
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec)
                    .getEncoded();

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder()
                    .encodeToString(cipher.doFinal(rawPassword.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt library password", exception);
        }
    }
}
