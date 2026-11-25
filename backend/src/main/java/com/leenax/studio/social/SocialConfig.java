package com.leenax.studio.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocialConfig {

    @Bean
    SocialHttp socialHttp(ObjectMapper mapper) {
        return new SocialHttp(mapper);
    }

    @Bean
    YouTubeClient youTubeClient(SocialHttp http, SocialProperties props) {
        return new YouTubeClient(http, props);
    }

    @Bean
    MetaClient metaClient(SocialHttp http, SocialProperties props) {
        return new MetaClient(http, props);
    }

    @Bean
    TikTokClient tikTokClient(SocialHttp http, SocialProperties props) {
        return new TikTokClient(http, props);
    }
}
