package com.leenax.studio.ai.credits;

import com.leenax.studio.ai.provider.BalanceSnapshot;
import com.leenax.studio.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CreditService {

    public static final Duration EXPIRY_WARNING = Duration.ofDays(7);

    private final CreditBalanceRepository balances;
    private final UsageLogRepository usage;

    public CreditService(CreditBalanceRepository balances, UsageLogRepository usage) {
        this.balances = balances;
        this.usage = usage;
    }

    public record Total(String unit, BigDecimal amount) {
    }

    /** Available (non-expired) amount per unit, largest first. */
    public static List<Total> totals(Collection<CreditBalance> rows, Instant now) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (CreditBalance b : rows) {
            if (b.isExpired(now)) continue;
            sums.merge(b.getUnit(), b.getAmount(), BigDecimal::add);
        }
        return sums.entrySet().stream()
                .map(e -> new Total(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Total::amount).reversed())
                .toList();
    }

    public static boolean expiringSoon(CreditBalance b, Instant now) {
        return b.getExpiresAt() != null && !b.isExpired(now) && b.getExpiresAt().isBefore(now.plus(EXPIRY_WARNING))
                && b.getAmount().signum() > 0;
    }

    @Transactional(readOnly = true)
    public List<CreditBalance> forAccount(UUID accountId) {
        return balances.findByAccountIdOrderByExpiresAtAscLabelAsc(accountId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<CreditBalance>> forAccounts(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) return Map.of();
        return balances.findByAccountIdIn(accountIds).stream()
                .collect(Collectors.groupingBy(CreditBalance::getAccountId));
    }

    /** Replaces the provider-reported buckets with a fresh snapshot. Manual buckets are untouched. */
    @Transactional
    public void replaceProviderBalances(UUID accountId, List<BalanceSnapshot> snapshots) {
        balances.deleteByAccountIdAndSource(accountId, CreditBalance.Source.PROVIDER);
        balances.flush();
        for (BalanceSnapshot s : snapshots) {
            balances.save(new CreditBalance(accountId, s.label(), s.free() ? CreditBalance.Kind.FREE : CreditBalance.Kind.PAID,
                    s.amount(), s.unit(), s.expiresAt(), CreditBalance.Source.PROVIDER));
        }
    }

    @Transactional
    public CreditBalance addManual(UUID accountId, String label, CreditBalance.Kind kind, BigDecimal amount, String unit,
                                   Instant expiresAt) {
        return balances.save(new CreditBalance(accountId, label.trim(), kind, amount, unit, expiresAt, CreditBalance.Source.MANUAL));
    }

    @Transactional
    public CreditBalance updateManual(UUID id, String label, CreditBalance.Kind kind, BigDecimal amount, String unit,
                                      Instant expiresAt, boolean clearExpiry) {
        CreditBalance b = manual(id);
        if (label != null) b.setLabel(label.trim());
        if (kind != null) b.setKind(kind);
        if (amount != null) b.setAmount(amount);
        if (unit != null) b.setUnit(unit);
        if (clearExpiry) b.setExpiresAt(null);
        else if (expiresAt != null) b.setExpiresAt(expiresAt);
        return b;
    }

    @Transactional
    public void deleteManual(UUID id) {
        balances.delete(manual(id));
    }

    private CreditBalance manual(UUID id) {
        CreditBalance b = balances.findById(id).orElseThrow(() -> ApiException.notFound("Balance"));
        if (b.getSource() != CreditBalance.Source.MANUAL) {
            throw ApiException.badRequest("This balance comes from the provider. Use Sync to refresh it.");
        }
        return b;
    }

    /**
     * Subtracts spending from manually tracked buckets of the same unit:
     * free credits first, then whichever expires soonest. Provider-synced
     * balances are left alone; they are re-read from the provider instead.
     */
    @Transactional
    public void deductManual(UUID accountId, BigDecimal cost, String unit) {
        if (accountId == null || cost == null || cost.signum() <= 0 || unit == null) return;
        Instant now = Instant.now();
        List<CreditBalance> candidates = new ArrayList<>(balances.findByAccountIdOrderByExpiresAtAscLabelAsc(accountId).stream()
                .filter(b -> b.getSource() == CreditBalance.Source.MANUAL && unit.equals(b.getUnit())
                        && !b.isExpired(now) && b.getAmount().signum() > 0)
                .toList());
        candidates.sort(Comparator
                .comparing((CreditBalance b) -> b.getKind() == CreditBalance.Kind.FREE ? 0 : 1)
                .thenComparing(b -> b.getExpiresAt() == null ? Instant.MAX : b.getExpiresAt()));
        BigDecimal remaining = cost;
        for (CreditBalance b : candidates) {
            if (remaining.signum() <= 0) break;
            BigDecimal take = b.getAmount().min(remaining);
            b.setAmount(b.getAmount().subtract(take));
            remaining = remaining.subtract(take);
        }
    }

    @Transactional
    public void recordUsage(UsageLog log) {
        usage.save(log);
    }
}
