package com.leenax.studio.editor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EditorConfig {

    @Bean
    RendererClient rendererClient(@Value("${leenax.renderer.url:}") String url,
                                  @Value("${leenax.renderer.token:}") String token,
                                  ObjectMapper mapper) {
        return new RendererClient(url, token, mapper);
    }
}
