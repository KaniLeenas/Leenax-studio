package com.leenax.studio.access;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class AccessConfig {

    /** Wraps the pool Spring Boot creates, so JPA, JdbcTemplate and Flyway all go through it. */
    @Bean
    static BeanPostProcessor actorDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof ActorDataSource)) {
                    return new ActorDataSource(ds);
                }
                return bean;
            }
        };
    }
}
