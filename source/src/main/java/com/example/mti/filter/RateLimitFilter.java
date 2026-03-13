package com.example.mti.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(2)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ConcurrentHashMap<String, Bucket> rateLimitBuckets;

    public RateLimitFilter(ConcurrentHashMap<String, Bucket> rateLimitBuckets) {
        this.rateLimitBuckets = rateLimitBuckets;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String clientIp = httpRequest.getRemoteAddr();
        Bucket bucket = rateLimitBuckets.computeIfAbsent(clientIp, ip -> createBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        log.warn("Rate limit exceeded clientIp={} requestId={}", clientIp, requestId);

        String now = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        httpResponse.setStatus(429);
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write(
                "{\"meta\":{\"request_id\":\"" + requestId + "\",\"request_timestamp\":\"" + now + "\"}," +
                "\"data\":{\"error_code\":\"ERR_106\",\"title\":\"Too Many Requests\"," +
                "\"message\":\"Rate limit exceeded, try again later\"}}"
        );
    }

    private Bucket createBucket() {
        Bandwidth bandwidth = Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
