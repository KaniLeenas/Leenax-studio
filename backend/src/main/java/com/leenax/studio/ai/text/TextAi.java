package com.leenax.studio.ai.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.TextProvider;
import com.leenax.studio.autoedit.LlmJson;
import com.leenax.studio.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * One place for "ask the text model" calls used by the script writer and the
 * storyboard: picks the account's provider, records usage, and reads JSON
 * replies (retrying once when a reply can't be read).
 */
@Service
public class TextAi {

    private final AccountService accounts;
    private final ProviderRegistry registry;
    private final UsageRecorder usage;

    public TextAi(AccountService accounts, ProviderRegistry registry, UsageRecorder usage) {
        this.accounts = accounts;
        this.registry = registry;
        this.usage = usage;
    }

    public String ask(UUID accountId, String model, String system, String user) {
        if (accountId == null || model == null || model.isBlank()) {
            throw ApiException.badRequest("Choose an AI text account and model (OpenAI, Claude or Gemini)");
        }
        var acc = accounts.forUse(accountId);
        TextProvider text = registry.text(acc.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(acc.account().getProvider().displayName() + " can't write text. Use OpenAI, Anthropic or Gemini."));
        try {
            String reply = text.complete(model.strip(), system, user, acc.credentials());
            usage.record(acc.account(), model, "TEXT", null, null, null, false, true);
            return reply;
        } catch (ProviderException e) {
            usage.record(acc.account(), model, "TEXT", null, null, null, false, false);
            throw e;
        }
    }

    public JsonNode askJson(UUID accountId, String model, String system, String user) {
        IllegalArgumentException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return LlmJson.extract(ask(accountId, model, system, user));
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw ApiException.badRequest("The AI reply couldn't be read (" + last.getMessage() + "). Try again or use another model.");
    }
}
