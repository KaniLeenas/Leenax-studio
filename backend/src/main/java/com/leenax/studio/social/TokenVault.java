package com.leenax.studio.social;

import com.leenax.studio.ai.crypto.SecretCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The only place social OAuth tokens are encrypted or decrypted (AES-256-GCM
 * with LEENAX_MASTER_KEY). The account id and token kind are bound in as
 * associated data, so a ciphertext copied to another row or column fails.
 */
@Service
public class TokenVault {

    public record Tokens(String accessToken, String refreshToken) {
        @Override
        public String toString() {
            return "Tokens[hidden]";
        }
    }

    private final SecretCipher cipher;
    private final SocialTokenRepository tokens;

    public TokenVault(SecretCipher cipher, SocialTokenRepository tokens) {
        this.cipher = cipher;
        this.tokens = tokens;
    }

    static String aad(UUID accountId, String kind) {
        return "social_account:" + accountId + ":" + kind;
    }

    @Transactional
    public void store(UUID accountId, String accessToken, String refreshToken) {
        byte[] access = cipher.encrypt(accessToken, aad(accountId, "access"));
        byte[] refresh = refreshToken == null || refreshToken.isBlank() ? null : cipher.encrypt(refreshToken, aad(accountId, "refresh"));
        tokens.findById(accountId).ifPresentOrElse(
                t -> t.replace(access, refresh),
                () -> tokens.save(new SocialToken(accountId, access, refresh)));
    }

    @Transactional(readOnly = true)
    public Optional<Tokens> load(UUID accountId) {
        return tokens.findById(accountId).map(t -> {
            try {
                String access = cipher.decrypt(t.getAccessCiphertext(), aad(accountId, "access"));
                String refresh = t.getRefreshCiphertext() == null ? null : cipher.decrypt(t.getRefreshCiphertext(), aad(accountId, "refresh"));
                return new Tokens(access, refresh);
            } catch (IllegalStateException e) {
                throw new SocialException(SocialException.Kind.RECONNECT,
                        "Saved sign-in could not be decrypted (was LEENAX_MASTER_KEY changed?). Reconnect the account.");
            }
        });
    }

    @Transactional
    public void delete(UUID accountId) {
        if (tokens.existsById(accountId)) tokens.deleteById(accountId);
    }
}
