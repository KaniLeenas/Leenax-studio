package com.leenax.studio.ai.accounts;

import com.leenax.studio.ai.credits.CreditBalance;
import com.leenax.studio.ai.credits.CreditService;
import com.leenax.studio.ai.provider.ProviderCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/accounts")
public class AccountController {

    private final AccountService service;
    private final CreditService credits;

    public AccountController(AccountService service, CreditService credits) {
        this.service = service;
        this.credits = credits;
    }

    // ---- DTOs ----------------------------------------------------------------

    public record BalanceResponse(UUID id, String label, CreditBalance.Kind kind, BigDecimal amount, String unit,
                                  Instant expiresAt, CreditBalance.Source source, boolean expired,
                                  boolean expiringSoon, Instant updatedAt) {
        public static BalanceResponse of(CreditBalance b, Instant now) {
            return new BalanceResponse(b.getId(), b.getLabel(), b.getKind(), b.getAmount(), b.getUnit(), b.getExpiresAt(),
                    b.getSource(), b.isExpired(now), CreditService.expiringSoon(b, now), b.getUpdatedAt());
        }
    }

    /** The API key itself is never part of any response. */
    public record AccountResponse(UUID id, ProviderCode provider, String providerName, List<String> capabilities,
                                  boolean balanceApi, String name, AiAccount.Status status, boolean isDefault,
                                  String maskedKey, BigDecimal lowCreditThreshold, Instant lastVerifiedAt,
                                  String lastError, Instant balanceSyncedAt, List<BalanceResponse> balances,
                                  List<CreditService.Total> totals, boolean lowCredit, boolean expiringSoon,
                                  Instant createdAt) {
    }

    public record CreateAccountRequest(@NotNull ProviderCode provider,
                                       @NotBlank @Size(max = 80) String name,
                                       @NotBlank @Size(max = 8000) String apiKey,
                                       boolean makeDefault) {
        @Override
        public String toString() {
            return "CreateAccountRequest[" + provider + ", " + name + ", ***]";
        }
    }

    public record UpdateAccountRequest(@Size(min = 1, max = 80) String name,
                                       @DecimalMin("0") BigDecimal lowCreditThreshold,
                                       boolean clearLowCreditThreshold,
                                       Boolean enabled) {
    }

    public record ReplaceKeyRequest(@NotBlank @Size(max = 8000) String apiKey) {
        @Override
        public String toString() {
            return "ReplaceKeyRequest[***]";
        }
    }

    AccountResponse toResponse(AiAccount a, List<CreditBalance> rows) {
        Instant now = Instant.now();
        String masked = a.getKeyHint() == null || a.getKeyHint().isEmpty() ? "••••••••" : "••••••••" + a.getKeyHint();
        return new AccountResponse(a.getId(), a.getProvider(), a.getProvider().displayName(),
                a.getProvider().capabilities().stream().map(Enum::name).sorted().toList(),
                a.getProvider().hasBalanceApi(), a.getName(), a.getStatus(), a.isDefault(), masked,
                a.getLowCreditThreshold(), a.getLastVerifiedAt(), a.getLastError(), a.getBalanceSyncedAt(),
                rows.stream().map(b -> BalanceResponse.of(b, now)).toList(),
                CreditService.totals(rows, now), AccountService.lowCredit(a, rows, now),
                rows.stream().anyMatch(b -> CreditService.expiringSoon(b, now)), a.getCreatedAt());
    }

    AccountResponse respond(AiAccount a) {
        return toResponse(a, credits.forAccount(a.getId()));
    }

    // ---- endpoints -------------------------------------------------------------

    @GetMapping
    public List<AccountResponse> list() {
        List<AiAccount> all = service.list();
        Map<UUID, List<CreditBalance>> rows = credits.forAccounts(all.stream().map(AiAccount::getId).toList());
        return all.stream().map(a -> toResponse(a, rows.getOrDefault(a.getId(), List.of()))).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return respond(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest req) {
        return respond(service.create(req.provider(), req.name(), req.apiKey(), req.makeDefault()));
    }

    @PatchMapping("/{id}")
    public AccountResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest req) {
        return respond(service.update(id, req.name(), req.lowCreditThreshold(), req.clearLowCreditThreshold(), req.enabled()));
    }

    @PutMapping("/{id}/key")
    public AccountResponse replaceKey(@PathVariable UUID id, @Valid @RequestBody ReplaceKeyRequest req) {
        return respond(service.replaceKey(id, req.apiKey()));
    }

    @PostMapping("/{id}/verify")
    public AccountResponse verify(@PathVariable UUID id) {
        return respond(service.verify(id));
    }

    @PostMapping("/{id}/sync-balance")
    public AccountResponse sync(@PathVariable UUID id) {
        return respond(service.syncBalances(id));
    }

    @PostMapping("/{id}/default")
    public AccountResponse makeDefault(@PathVariable UUID id) {
        return respond(service.setDefault(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/{id}/models")
    public List<String> textModels(@PathVariable UUID id) {
        return service.textModels(id);
    }
}
