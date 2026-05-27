package com.ssuai.domain.library.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request forwarded to oasis.ssu.ac.kr/pyxis-api/api/login.
 * The password must already be AES-encrypted by the caller (frontend),
 * matching the encryption the oasis web client applies.
 * ssuAI backend passes it through without decrypting — never logs it.
 */
public record LibraryCredentialLoginRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}
