package com.polestar.mti.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(2)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Long rateLimitRequestsPerMinute;

    public RateLimitFilter(Long rateLimitRequestsPerMinute) {
        this.rateLimitRequestsPerMinute = rateLimitRequestsPerMinute;
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(rateLimitRequestsPerMinute)
                .refillGreedy(rateLimitRequestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String apiKey = httpRequest.getHeader("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "anonymous";
        }

        Bucket bucket = buckets.computeIfAbsent(apiKey, k -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded apiKey={} uri={}", apiKey, httpRequest.getRequestURI());
            Object requestIdAttr = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
            String requestId = requestIdAttr != null ? requestIdAttr.toString() : java.util.UUID.randomUUID().toString();
            String now = Instant.now().toString();

            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                    "{\"meta\":{\"request_id\":\"" + requestId + "\",\"request_timestamp\":\"" + now + "\"}," +
                    "\"data\":{\"error_code\":\"ERR_429\",\"title\":\"Too Many Requests\"," +
                    "\"message\":\"Rate limit exceeded. Maximum 100 requests per minute.\"}}"
            );
        }
    }
}
