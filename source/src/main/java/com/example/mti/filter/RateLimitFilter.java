package com.example.mti.filter;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
@Order(2)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Supplier<Bucket> bucketSupplier;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(Supplier<Bucket> bucketSupplier) {
        this.bucketSupplier = bucketSupplier;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String ip = request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(ip, k -> bucketSupplier.get());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String requestId = (String) ((HttpServletRequest) request).getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        if (requestId == null) {
            requestId = "unknown";
        }
        log.warn("Rate limit exceeded ip={} requestId={}", ip, requestId);
        httpResp.setStatus(429);
        httpResp.setContentType("application/json");
        httpResp.getWriter().write("{\"meta\":{\"request_id\":\"" + requestId + "\",\"request_timestamp\":\"" + Instant.now() + "\"},\"data\":{\"error_code\":\"RATE_LIMIT\",\"title\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Maximum 100 requests per minute.\"}}");
    }
}
