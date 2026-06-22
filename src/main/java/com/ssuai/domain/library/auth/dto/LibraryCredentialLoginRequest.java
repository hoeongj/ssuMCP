package com.ssuai.domain.library.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request forwarded to oasis.ssu.ac.kr/pyxis-api/api/login.
 * The password must already be AES-encrypted by the caller (frontend),
 * matching the encryption the oasis web client applies.
 * ssuAI backend passes it through without decrypting — never logs it.
 *
 * <p>Size caps are generous bounds against unbounded request bodies (security
 * review Wave 3, Codex #9): {@code loginId} is a student id (≤ ~100 chars), and
 * {@code password} is an AES-CBC + Base64 blob — a Base64 ciphertext of any
 * realistic raw password is well under 2000 chars, so 2000 never rejects a
 * legitimate login while still bounding the body.</p>
 */
public record LibraryCredentialLoginRequest(
        @NotBlank @Size(max = 100) String loginId,
        @NotBlank @Size(max = 2000) String password
) {
}
