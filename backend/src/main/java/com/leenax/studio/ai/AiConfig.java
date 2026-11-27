package com.leenax.studio.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leenax.studio.ai.crypto.SecretCipher;
import com.leenax.studio.ai.provider.ProviderHttp;
import com.leenax.studio.ai.provider.ProviderRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AiConfig {

    /** Fails fast at startup with a clear message when LEENAX_MASTER_KEY is missing or malformed. */
    @Bean
    SecretCipher secretCipher(@Value("${leenax.security.master-key:}") String masterKey) {
        return SecretCipher.fromBase64(masterKey);
    }

    @Bean
    ProviderHttp providerHttp(ObjectMapper mapper) {
        return new ProviderHttp(mapper);
    }

    @Bean
    ProviderRegistry providerRegistry(ProviderHttp http,
                                     @Value("${leenax.ai.ollama-url:http://localhost:11434}") String ollamaUrl,
                                     @Value("${leenax.ai.freellmapi-url:http://freellmapi:3001}") String freeLlmApiUrl) {
        return ProviderRegistry.defaults(http, ollamaUrl, freeLlmApiUrl);
    }
}
