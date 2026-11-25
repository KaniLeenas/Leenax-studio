package com.leenax.studio.ai.credits;

import com.leenax.studio.ai.accounts.AccountController.BalanceResponse;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
public class CreditController {

    private static final String UNITS = "CREDITS|USD|SECONDS|UNITS";

    private final CreditService credits;
    private final AccountService accounts;
    private final UsageLogRepository usage;

    public CreditController(CreditService credits, AccountService accounts, UsageLogRepository usage) {
        this.credits = credits;
        this.accounts = accounts;
        this.usage = usage;
    }

    public record BalanceRequest(@NotBlank @Size(max = 120) String label,
                                 @NotNull CreditBalance.Kind kind,
                                 @NotNull @DecimalMin("0") BigDecimal amount,
                                 @NotNull @Pattern(regexp = UNITS) String unit,
                                 Instant expiresAt) {
    }

    public record BalancePatch(@Size(min = 1, max = 120) String label, CreditBalance.Kind kind,
                               @DecimalMin("0") BigDecimal amount, @Pattern(regexp = UNITS) String unit,
                               Instant expiresAt, boolean clearExpiry) {
    }

    public record UsageResponse(UUID id, UUID accountId, String accountName, ProviderCode provider, String providerName,
                                String modelCode, String capability, UUID generationJobId, BigDecimal outputSeconds,
                                BigDecimal cost, String costUnit, UsageLog.CostSource costSource, boolean success,
                                Instant createdAt) {
        static UsageResponse of(UsageLog u) {
            return new UsageResponse(u.getId(), u.getAccountId(), u.getAccountName(), u.getProvider(),
                    u.getProvider().displayName(), u.getModelCode(), u.getCapability(), u.getGenerationJobId(),
                    u.getOutputSeconds(), u.getCost(), u.getCostUnit(), u.getCostSource(), u.isSuccess(), u.getCreatedAt());
        }
    }

    public record SpendLine(UUID accountId, String accountName, ProviderCode provider, String providerName,
                            int generations, int failed, BigDecimal seconds, Map<String, BigDecimal> costByUnit) {
    }

    public record UsageSummary(int days, int generations, int failed, BigDecimal seconds,
                               Map<String, BigDecimal> costByUnit, List<SpendLine> byAccount) {
    }

    // ---- balances ------------------------------------------------------------

    @PostMapping("/accounts/{accountId}/balances")
    @ResponseStatus(HttpStatus.CREATED)
    public BalanceResponse add(@PathVariable UUID accountId, @Valid @RequestBody BalanceRequest req) {
        accounts.get(accountId);
        return BalanceResponse.of(credits.addManual(accountId, req.label(), req.kind(), req.amount(), req.unit(),
                req.expiresAt()), Instant.now());
    }

    @PatchMapping("/balances/{id}")
    public BalanceResponse update(@PathVariable UUID id, @Valid @RequestBody BalancePatch req) {
        return BalanceResponse.of(credits.updateManual(id, req.label(), req.kind(), req.amount(), req.unit(),
                req.expiresAt(), req.clearExpiry()), Instant.now());
    }

    @DeleteMapping("/balances/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        credits.deleteManual(id);
    }

    // ---- usage ---------------------------------------------------------------

    @GetMapping("/usage")
    @Transactional(readOnly = true)
    public PageResponse<UsageResponse> usage(@RequestParam(required = false) UUID accountId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        PageRequest p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = accountId == null ? usage.findAllByOrderByCreatedAtDesc(p) : usage.findByAccountIdOrderByCreatedAtDesc(accountId, p);
        return PageResponse.of(result.map(UsageResponse::of));
    }

    @GetMapping("/usage/summary")
    @Transactional(readOnly = true)
    public UsageSummary summary(@RequestParam(defaultValue = "30") int days) {
        int d = Math.min(Math.max(days, 1), 366);
        List<UsageLog> rows = usage.findByCreatedAtAfter(Instant.now().minus(Duration.ofDays(d)));
        Map<String, SpendAcc> byAccount = new LinkedHashMap<>();
        SpendAcc total = new SpendAcc(null, "All", null);
        for (UsageLog u : rows) {
            String key = (u.getAccountId() != null ? u.getAccountId().toString() : "deleted") + "|" + u.getAccountName();
            byAccount.computeIfAbsent(key, k -> new SpendAcc(u.getAccountId(), u.getAccountName(), u.getProvider())).add(u);
            total.add(u);
        }
        List<SpendLine> lines = new ArrayList<>(byAccount.values().stream().map(SpendAcc::line).toList());
        lines.sort(Comparator.comparingInt(SpendLine::generations).reversed());
        return new UsageSummary(d, total.generations, total.failed, total.seconds, total.cost, lines);
    }

    private static final class SpendAcc {
        final UUID accountId;
        final String name;
        final ProviderCode provider;
        int generations;
        int failed;
        BigDecimal seconds = BigDecimal.ZERO;
        final Map<String, BigDecimal> cost = new LinkedHashMap<>();

        SpendAcc(UUID accountId, String name, ProviderCode provider) {
            this.accountId = accountId;
            this.name = name;
            this.provider = provider;
        }

        void add(UsageLog u) {
            generations++;
            if (!u.isSuccess()) failed++;
            if (u.getOutputSeconds() != null) seconds = seconds.add(u.getOutputSeconds());
            if (u.getCost() != null && u.getCostUnit() != null) cost.merge(u.getCostUnit(), u.getCost(), BigDecimal::add);
        }

        SpendLine line() {
            return new SpendLine(accountId, name, provider, provider == null ? null : provider.displayName(),
                    generations, failed, seconds, cost);
        }
    }
}
