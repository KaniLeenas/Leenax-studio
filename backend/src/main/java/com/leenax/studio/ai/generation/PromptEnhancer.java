package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.TextProvider;
import com.leenax.studio.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Rewrites a rough idea into a detailed video prompt using one of your text model accounts. */
@Service
public class PromptEnhancer {

    static final String SYSTEM = """
            You write prompts for AI video generators for a YouTube and social media creator.
            Rewrite the user's idea as one vivid, concrete video prompt:
            - Describe the subject, the action, the setting, the camera (shot size and movement), lighting, mood and visual style.
            - Keep it under 120 words, in present tense, as a single paragraph.
            - Keep every specific detail the user gave. Do not invent brand names, real people or text overlays.
            - If the user wrote in Tamil or mentions Tamil culture, keep names, places and cultural details accurate, and keep any spoken lines in Tamil; write the visual description in English.
            Reply with the prompt only: no title, no quotes, no explanation.
            """;

    private final AccountService accounts;
    private final ProviderRegistry registry;

    public PromptEnhancer(AccountService accounts, ProviderRegistry registry) {
        this.accounts = accounts;
        this.registry = registry;
    }

    public String enhance(UUID accountId, String model, String prompt, String language, String targetModel) {
        var usable = accounts.forUse(accountId);
        TextProvider text = registry.text(usable.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(usable.account().getProvider().displayName() + " cannot be used for prompt writing"));
        StringBuilder user = new StringBuilder("Idea: ").append(prompt.strip());
        if (targetModel != null && !targetModel.isBlank()) user.append("\nVideo model: ").append(targetModel.strip());
        if ("TA".equalsIgnoreCase(language)) user.append("\nThe video is for a Tamil-language channel.");
        String result = text.complete(model.strip(), SYSTEM, user.toString(), usable.credentials());
        return clean(result);
    }

    /** Strips wrapping quotes or a "Prompt:" label that models sometimes add anyway. */
    static String clean(String s) {
        String out = s.strip();
        if (out.toLowerCase().startsWith("prompt:")) out = out.substring(7).strip();
        if (out.length() > 1 && (out.startsWith("\"") && out.endsWith("\"") || out.startsWith("“") && out.endsWith("”"))) {
            out = out.substring(1, out.length() - 1).strip();
        }
        return out.length() > 4000 ? out.substring(0, 4000) : out;
    }
}
