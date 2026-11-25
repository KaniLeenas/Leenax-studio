package com.leenax.studio.ai.generation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CostEstimatorTest {

    @Test
    void flatRatePerSecond() {
        var e = CostEstimator.estimate(Map.of("unit", "CREDITS", "rates", List.of(Map.of("perSecond", 12))), 5, null, null);
        assertEquals(new BigDecimal("60"), e.amount());
        assertEquals("CREDITS", e.unit());
    }

    @Test
    void audioAndResolutionSpecificRatesWin() {
        Map<String, Object> veo = Map.of("unit", "CREDITS", "rates", List.of(
                Map.of("perSecond", 20, "audio", false), Map.of("perSecond", 40, "audio", true)));
        assertEquals(new BigDecimal("320"), CostEstimator.estimate(veo, 8, null, true).amount());
        assertEquals(new BigDecimal("160"), CostEstimator.estimate(veo, 8, null, false).amount());
        assertEquals(new BigDecimal("160"), CostEstimator.estimate(veo, 8, null, null).amount());

        Map<String, Object> kling = Map.of("unit", "CREDITS", "rates", List.of(
                Map.of("perSecond", 12.56, "resolution", "720p"), Map.of("perSecond", 18.8, "resolution", "1080p")));
        assertEquals(new BigDecimal("63"), CostEstimator.estimate(kling, 5, "720p", null).amount()); // 62.8 rounded up
        assertEquals(new BigDecimal("94"), CostEstimator.estimate(kling, 5, "1080p", null).amount());
        assertNull(CostEstimator.estimate(kling, 5, "4k", null));
    }

    @Test
    void minimumAndUsdRounding() {
        Map<String, Object> flash = Map.of("unit", "CREDITS", "minimum", 29, "rates", List.of(Map.of("perSecond", 9.4)));
        assertEquals(new BigDecimal("29"), CostEstimator.estimate(flash, 3, null, null).amount());
        Map<String, Object> usd = Map.of("unit", "USD", "rates", List.of(Map.of("perSecond", 0.075)));
        assertEquals(new BigDecimal("0.38"), CostEstimator.estimate(usd, 5, null, null).amount());
    }

    @Test
    void unknownPriceOrDuration() {
        assertNull(CostEstimator.estimate(Map.of("unit", "USD", "rates", List.of()), 5, null, null));
        assertNull(CostEstimator.estimate(Map.of("rates", List.of(Map.of("perSecond", 1))), null, null, null));
        assertNull(CostEstimator.estimate(null, 5, null, null));
    }
}
