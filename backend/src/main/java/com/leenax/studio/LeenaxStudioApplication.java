package com.leenax.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LeenaxStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeenaxStudioApplication.class, args);
    }
}
