package com.leenax.studio.ai.credits;

import com.leenax.studio.ai.accounts.AiAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Writes a usage log row for voice, image, transcription and sound calls. Never fails the caller. */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);
    private final CreditService credits;

    public UsageRecorder(CreditService credits) {
        this.credits = credits;
    }

    public void record(AiAccount account, String model, String capability, Double outputSeconds, Number cost, String costUnit,
                       boolean estimated, boolean success) {
        try {
            UsageLog.CostSource source = cost == null ? UsageLog.CostSource.UNKNOWN
                    : estimated ? UsageLog.CostSource.ESTIMATED : UsageLog.CostSource.PROVIDER_REPORTED;
            credits.recordUsage(new UsageLog(account.getId(), account.getName(), account.getProvider(),
                    model == null ? "default" : model, capability, null,
                    outputSeconds == null ? null : BigDecimal.valueOf(outputSeconds).setScale(2, java.math.RoundingMode.HALF_UP),
                    cost == null ? null : new BigDecimal(cost.toString()), cost == null ? null : costUnit, source, success));
        } catch (RuntimeException e) {
            log.warn("Could not record usage: {}", e.getMessage());
        }
    }
}
