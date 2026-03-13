package com.example.mti.filter;

import com.example.mti.config.RateLimitConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(2)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitConfig.RateLimitProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitConfig.RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        String clientIp;
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            clientIp = xForwardedFor.split(",")[0].trim();
        } else {
            clientIp = request.getRemoteAddr();
        }

        Bucket bucket = buckets.computeIfAbsent(clientIp, key -> buildBucket());
        boolean consumed = bucket.tryConsume(1L);

        if (!consumed) {
            log.warn("Rate limit exceeded for ip={}", clientIp);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"meta\":null,\"data\":{\"error_code\":\"ERR_429\",\"title\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Max 100 requests per minute.\"}}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Bucket buildBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.getCapacity())
                        .refillGreedy(properties.getRefillTokens(), Duration.ofSeconds(properties.getRefillPeriodSeconds()))
                        .build())
                .build();
    }
}
