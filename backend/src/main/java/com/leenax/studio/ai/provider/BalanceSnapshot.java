package com.leenax.studio.ai.provider;

import java.math.BigDecimal;
import java.time.Instant;

/** One balance bucket as reported by a provider's API. */
public record BalanceSnapshot(String label, boolean free, BigDecimal amount, String unit, Instant expiresAt) {
}
