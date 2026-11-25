package com.leenax.studio.audio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AudioConfig {

    @Bean
    public Ffmpeg ffmpeg(@Value("${leenax.media.ffmpeg-path:ffmpeg}") String path,
                         @Value("${leenax.media.ffmpeg-timeout-seconds:900}") long timeoutSeconds) {
        return new Ffmpeg(path, timeoutSeconds);
    }
}
