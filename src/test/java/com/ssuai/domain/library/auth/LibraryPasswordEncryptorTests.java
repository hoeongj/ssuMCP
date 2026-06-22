package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LibraryPasswordEncryptorTests {

    private final LibraryPasswordEncryptor encryptor = new LibraryPasswordEncryptor();

    @Test
    void producesExpectedDeterministicCiphertext() {
        // Golden vector: fixed key (PBKDF2 over constant material/salt) + fixed IV
        // ⇒ deterministic ciphertext. Pins the output so the PBKDF2-key-caching
        // refactor cannot silently change the encryption result.
        assertThat(encryptor.encrypt("P@ssw0rd!")).isEqualTo("ep3JcHd50SSySIxrIPNiYQ==");
    }

    @Test
    void reuseOfCachedKeyYieldsIdenticalCiphertextAcrossCalls() {
        String first = encryptor.encrypt("same-input");
        String second = encryptor.encrypt("same-input");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void rejectsBlankPassword() {
        assertThatThrownBy(() -> encryptor.encrypt(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryptor.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
