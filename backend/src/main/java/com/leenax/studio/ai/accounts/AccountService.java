package com.leenax.studio.ai.accounts;

import com.leenax.studio.ai.credits.CreditBalance;
import com.leenax.studio.ai.credits.CreditService;
import com.leenax.studio.ai.crypto.SecretCipher;
import com.leenax.studio.ai.provider.BalanceSnapshot;
import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.ai.provider.ProviderCredentials;
import com.leenax.studio.ai.provider.ProviderErrorKind;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.TextProvider;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages provider accounts. Network calls to providers happen outside
 * database transactions; each state change is committed on its own.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AiAccountRepository accounts;
    private final CredentialVault vault;
    private final ProviderRegistry registry;
    private final CreditService credits;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public AccountService(AiAccountRepository accounts, CredentialVault vault, ProviderRegistry registry,
                          CreditService credits, AuditService audit, TransactionTemplate tx) {
        this.accounts = accounts;
        this.vault = vault;
        this.registry = registry;
        this.credits = credits;
        this.audit = audit;
        this.tx = tx;
    }

    public record Usable(AiAccount account, ProviderCredentials credentials) {
    }

    // ---- queries -----------------------------------------------------------

    public List<AiAccount> list() {
        return accounts.findAllByOrderByProviderAscCreatedAtAsc();
    }

    public AiAccount get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Account"));
    }

    /** Loads an account for a generation call; refuses disabled or invalid ones. */
    public Usable forUse(UUID id) {
        AiAccount a = get(id);
        if (a.getStatus() == AiAccount.Status.DISABLED) {
            throw new ApiException(ErrorCode.CONFLICT, "“" + a.getName() + "” is switched off. Turn it on in AI accounts & keys.");
        }
        if (a.getStatus() == AiAccount.Status.INVALID) {
            throw new ApiException(ErrorCode.CONFLICT, "The key for “" + a.getName() + "” was rejected. Replace it in AI accounts & keys.");
        }
        return new Usable(a, vault.load(id));
    }

    // ---- create / change ---------------------------------------------------

    public AiAccount create(ProviderCode provider, String name, String apiKey, boolean makeDefault) {
        final String credentialValue = provider == ProviderCode.OLLAMA ? "ollama-local" : apiKey;
        if (credentialValue == null || credentialValue.isBlank()) throw ApiException.badRequest("An API key is required for this provider.");
        String cleanName = name.trim();
        if (accounts.nameTaken(provider, cleanName, new UUID(0, 0))) {
            throw new ApiException(ErrorCode.CONFLICT, "You already have a " + provider.displayName() + " account called “" + cleanName + "”");
        }
        ProviderCredentials creds = new ProviderCredentials(credentialValue);

        // Check the key before saving it. A rejected key is never stored.
        String warning = null;
        try {
            registry.verify(provider, creds);
        } catch (ProviderException e) {
            if (!e.retryable()) throw e;
            warning = e.getMessage(); // provider down: save now, verify later
        }
        final String verifyWarning = warning;

        AiAccount saved = tx.execute(status -> {
            AiAccount a = new AiAccount(provider, cleanName);
            a.setKeyHint(SecretCipher.hint(credentialValue));
            if (verifyWarning == null) a.markVerified();
            else a.markFailed(AiAccount.Status.UNVERIFIED, verifyWarning);
            boolean first = accounts.findByProvider(provider).isEmpty();
            if (makeDefault || first) {
                accounts.clearDefault(provider);
                a.setDefault(true);
            }
            a = accounts.save(a);
            vault.store(a.getId(), credentialValue);
            audit.record("AI_ACCOUNT_ADDED", "ai_account", a.getId(), Map.of("provider", provider.name()));
            return a;
        });
        if (verifyWarning == null) syncBalancesQuietly(saved.getId(), creds);
        return get(saved.getId());
    }

    public AiAccount update(UUID id, String name, BigDecimal lowCreditThreshold, boolean clearThreshold, Boolean enabled) {
        return tx.execute(status -> {
            AiAccount a = get(id);
            if (name != null && !name.isBlank()) {
                if (accounts.nameTaken(a.getProvider(), name.trim(), id)) {
                    throw new ApiException(ErrorCode.CONFLICT, "That name is already used for another " + a.getProvider().displayName() + " account");
                }
                a.setName(name.trim());
            }
            if (clearThreshold) a.setLowCreditThreshold(null);
            else if (lowCreditThreshold != null) a.setLowCreditThreshold(lowCreditThreshold);
            if (enabled != null) {
                if (!enabled) a.setStatus(AiAccount.Status.DISABLED);
                else if (a.getStatus() == AiAccount.Status.DISABLED) a.setStatus(AiAccount.Status.UNVERIFIED);
            }
            return accounts.save(a);
        });
    }

    /** Replaces the key only if the provider accepts the new one. */
    public AiAccount replaceKey(UUID id, String apiKey) {
        AiAccount a = get(id);
        if (a.getProvider() == ProviderCode.OLLAMA) throw ApiException.badRequest("Ollama runs locally and does not use an API key.");
        ProviderCredentials creds = new ProviderCredentials(apiKey);
        registry.verify(a.getProvider(), creds);
        tx.executeWithoutResult(status -> {
            AiAccount fresh = get(id);
            vault.store(id, apiKey);
            fresh.setKeyHint(SecretCipher.hint(apiKey));
            fresh.markVerified();
            accounts.save(fresh);
            audit.record("AI_ACCOUNT_KEY_REPLACED", "ai_account", id, Map.of("provider", fresh.getProvider().name()));
        });
        syncBalancesQuietly(id, creds);
        return get(id);
    }

    /** Re-checks the stored key and refreshes balances. */
    public AiAccount verify(UUID id) {
        AiAccount a = get(id);
        ProviderCredentials creds = vault.load(id);
        try {
            registry.verify(a.getProvider(), creds);
            tx.executeWithoutResult(s -> {
                AiAccount fresh = get(id);
                if (fresh.getStatus() != AiAccount.Status.DISABLED) fresh.markVerified();
                accounts.save(fresh);
            });
        } catch (ProviderException e) {
            tx.executeWithoutResult(s -> {
                AiAccount fresh = get(id);
                fresh.markFailed(e.kind() == ProviderErrorKind.INVALID_CREDENTIALS ? AiAccount.Status.INVALID
                        : fresh.getStatus() == AiAccount.Status.DISABLED ? AiAccount.Status.DISABLED : AiAccount.Status.UNVERIFIED,
                        e.getMessage());
                accounts.save(fresh);
            });
            throw e;
        }
        syncBalancesQuietly(id, creds);
        return get(id);
    }

    /** Reads balances from the provider's own API (where one exists). */
    public AiAccount syncBalances(UUID id) {
        AiAccount a = get(id);
        if (!a.getProvider().hasBalanceApi()) {
            throw ApiException.badRequest(a.getProvider().displayName() + " has no balance API. Enter your balance manually.");
        }
        syncBalances(a, vault.load(id));
        return get(id);
    }

    private void syncBalances(AiAccount a, ProviderCredentials creds) {
        List<BalanceSnapshot> snapshots = registry.balances(a.getProvider(), creds);
        tx.executeWithoutResult(s -> {
            credits.replaceProviderBalances(a.getId(), snapshots);
            AiAccount fresh = get(a.getId());
            fresh.setBalanceSyncedAt(Instant.now());
            accounts.save(fresh);
        });
    }

    public void syncBalancesQuietly(UUID id, ProviderCredentials creds) {
        try {
            AiAccount a = get(id);
            if (a.getProvider().hasBalanceApi()) syncBalances(a, creds);
        } catch (RuntimeException e) {
            log.info("Balance sync skipped for account {}: {}", id, e.getMessage());
        }
    }

    public void markInvalid(UUID id, String message) {
        tx.executeWithoutResult(s -> accounts.findById(id).ifPresent(a -> {
            a.markFailed(AiAccount.Status.INVALID, message);
            accounts.save(a);
        }));
    }

    public AiAccount setDefault(UUID id) {
        return tx.execute(s -> {
            AiAccount a = get(id);
            if (a.isDefault()) return a;
            accounts.clearDefault(a.getProvider());
            AiAccount fresh = get(id); // reload: the bulk update cleared the persistence context
            fresh.setDefault(true);
            return accounts.save(fresh);
        });
    }

    public void delete(UUID id) {
        tx.executeWithoutResult(s -> {
            AiAccount a = get(id);
            accounts.delete(a);
            audit.record("AI_ACCOUNT_DELETED", "ai_account", id, Map.of("provider", a.getProvider().name(), "name", a.getName()));
        });
    }

    public List<String> textModels(UUID id) {
        Usable u = forUse(id);
        TextProvider text = registry.text(u.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(u.account().getProvider().displayName() + " has no text models in Leenax Studio"));
        return text.listModels(u.credentials());
    }

    public static boolean lowCredit(AiAccount a, List<CreditBalance> rows, Instant now) {
        if (a.getLowCreditThreshold() == null) return false;
        var totals = CreditService.totals(rows, now);
        if (totals.isEmpty()) return false;
        return totals.get(0).amount().compareTo(a.getLowCreditThreshold()) < 0;
    }
}
