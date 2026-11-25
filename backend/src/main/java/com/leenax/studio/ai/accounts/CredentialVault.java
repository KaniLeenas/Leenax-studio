package com.leenax.studio.ai.accounts;

import com.leenax.studio.ai.crypto.SecretCipher;
import com.leenax.studio.ai.provider.ProviderCredentials;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The only place API keys are encrypted or decrypted. */
@Service
public class CredentialVault {

    private final SecretCipher cipher;
    private final ApiCredentialRepository credentials;

    public CredentialVault(SecretCipher cipher, ApiCredentialRepository credentials) {
        this.cipher = cipher;
        this.credentials = credentials;
    }

    private static String aad(UUID accountId) {
        return "ai_account:" + accountId;
    }

    @Transactional
    public void store(UUID accountId, String apiKey) {
        byte[] blob = cipher.encrypt(apiKey.strip(), aad(accountId));
        credentials.findById(accountId).ifPresentOrElse(
                c -> c.rotate(blob),
                () -> credentials.save(new ApiCredential(accountId, blob)));
    }

    @Transactional(readOnly = true)
    public ProviderCredentials load(UUID accountId) {
        ApiCredential c = credentials.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "This account has no API key saved"));
        try {
            return new ProviderCredentials(cipher.decrypt(c.getCiphertext(), aad(accountId)));
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }
}
