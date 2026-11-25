package com.leenax.studio.ai.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Estimates the cost of one generation from a model's pricing definition:
 *   {"unit":"CREDITS","minimum":29,"rates":[{"perSecond":20,"audio":false},{"perSecond":40,"audio":true},
 *                                           {"perSecond":12.56,"resolution":"720p"}]}
 * The most specific matching rate wins. Returns null when the price is unknown.
 */
public final class CostEstimator {

    private CostEstimator() {
    }

    public record Estimate(BigDecimal amount, String unit) {
    }

    @SuppressWarnings("unchecked")
    public static Estimate estimate(Map<String, Object> pricing, Integer seconds, String resolution, Boolean audio) {
        if (pricing == null || seconds == null || seconds <= 0) return null;
        Object ratesObj = pricing.get("rates");
        if (!(ratesObj instanceof List<?> rates) || rates.isEmpty()) return null;

        Map<String, Object> best = null;
        int bestScore = -1;
        for (Object o : rates) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> rate = (Map<String, Object>) raw;
            int score = 0;
            Object res = rate.get("resolution");
            if (res != null) {
                if (!res.toString().equalsIgnoreCase(String.valueOf(resolution))) continue;
                score += 2;
            }
            Object a = rate.get("audio");
            if (a != null) {
                boolean wantsAudio = Boolean.TRUE.equals(audio);
                if (Boolean.parseBoolean(a.toString()) != wantsAudio) continue;
                score += 1;
            }
            if (score > bestScore) {
                best = rate;
                bestScore = score;
            }
        }
        if (best == null || best.get("perSecond") == null) return null;

        BigDecimal perSecond = new BigDecimal(best.get("perSecond").toString());
        BigDecimal amount = perSecond.multiply(BigDecimal.valueOf(seconds));
        Object min = pricing.get("minimum");
        if (min != null) {
            BigDecimal minimum = new BigDecimal(min.toString());
            if (amount.compareTo(minimum) < 0) amount = minimum;
        }
        String unit = String.valueOf(pricing.getOrDefault("unit", "CREDITS"));
        // Credits are charged in whole units (rounded up); money keeps cents.
        amount = "USD".equals(unit) ? amount.setScale(2, RoundingMode.HALF_UP) : amount.setScale(0, RoundingMode.CEILING);
        return new Estimate(amount, unit);
    }
}
