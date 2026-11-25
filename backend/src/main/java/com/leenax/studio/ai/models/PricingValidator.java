package com.leenax.studio.ai.models;

import com.leenax.studio.common.ApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Accepts only the pricing shape CostEstimator understands, so bad input can't break estimates. */
final class PricingValidator {

    private static final Set<String> UNITS = Set.of("CREDITS", "USD", "SECONDS", "UNITS");

    private PricingValidator() {
    }

    static Map<String, Object> validate(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        String unit = String.valueOf(in.getOrDefault("unit", "CREDITS")).toUpperCase();
        if (!UNITS.contains(unit)) throw ApiException.badRequest("Unit must be one of " + UNITS);
        out.put("unit", unit);
        if (in.get("minimum") != null) out.put("minimum", nonNegative(in.get("minimum"), "minimum"));
        List<Map<String, Object>> rates = new ArrayList<>();
        if (in.get("rates") instanceof List<?> list) {
            if (list.size() > 20) throw ApiException.badRequest("Too many rates");
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> r)) throw ApiException.badRequest("Each rate must be an object");
                Map<String, Object> rate = new LinkedHashMap<>();
                rate.put("perSecond", nonNegative(r.get("perSecond"), "perSecond"));
                if (r.get("audio") != null) rate.put("audio", Boolean.parseBoolean(r.get("audio").toString()));
                if (r.get("resolution") != null) rate.put("resolution", r.get("resolution").toString());
                rates.add(rate);
            }
        }
        out.put("rates", rates);
        return out;
    }

    private static BigDecimal nonNegative(Object v, String field) {
        try {
            BigDecimal d = new BigDecimal(String.valueOf(v));
            if (d.signum() < 0) throw ApiException.badRequest(field + " cannot be negative");
            return d;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a number");
        }
    }
}
