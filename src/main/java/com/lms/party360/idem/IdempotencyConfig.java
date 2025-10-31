package com.lms.party360.idem;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfig {

    @Bean
    @ConditionalOnMissingBean
    public RedisClient redisClient() {
        // e.g., redis://redis:6379 — prefer using Spring Data Redis config if you already have it.
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
        return RedisClient.create(url);
    }

    @Bean
    public IdemCodec idemCodec(ObjectMapper objectMapper) {
        return new IdemCodec(objectMapper);
    }
}

