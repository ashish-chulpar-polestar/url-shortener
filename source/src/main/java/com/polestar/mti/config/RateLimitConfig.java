package com.polestar.mti.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Value("${app.rate-limit.requests-per-minute:100}")
    private long requestsPerMinute;

    @Bean
    public Long rateLimitRequestsPerMinute() {
        return requestsPerMinute;
    }
}
