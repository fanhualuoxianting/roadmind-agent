package com.roadmind.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateLimitService limiter;

    public RateLimitFilter(RateLimitProperties properties, RateLimitService limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) return true;
        String path = request.getRequestURI();
        return !(path.startsWith("/api/v1/conversations")
                || path.startsWith("/api/v1/workflows")
                || path.startsWith("/api/v1/demo/")
                || path.startsWith("/api/v1/agent-tasks/") && path.endsWith("/events")
                || path.startsWith("/internal/v1/tools/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Bucket bucket = bucket(request.getRequestURI());
        String subject = Optional.ofNullable(request.getUserPrincipal())
                .map(java.security.Principal::getName)
                .orElseGet(() -> request.getRemoteAddr() + ":" + request.getHeader("X-RoadMind-Internal-Token"));
        boolean allowed = limiter.tryAcquire(
                bucket.name(), subject, bucket.limit(properties), Duration.ofSeconds(properties.windowSeconds()));
        response.setHeader("X-RateLimit-Limit", Integer.toString(bucket.limit(properties)));
        if (!allowed) {
            response.setStatus(429);
            response.setHeader("Retry-After", Integer.toString(Math.max(1, properties.windowSeconds())));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后重试\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Bucket bucket(String path) {
        if (path.startsWith("/internal/v1/tools/")) return Bucket.TOOL;
        if (path.startsWith("/api/v1/workflows")) return Bucket.CONFIRMATION;
        if (path.endsWith("/events")) return Bucket.SSE;
        return Bucket.USER_MESSAGE;
    }

    private enum Bucket {
        USER_MESSAGE {
            @Override int limit(RateLimitProperties properties) { return properties.userMessageLimit(); }
        },
        TOOL {
            @Override int limit(RateLimitProperties properties) { return properties.toolLimit(); }
        },
        CONFIRMATION {
            @Override int limit(RateLimitProperties properties) { return properties.confirmationLimit(); }
        },
        SSE {
            @Override int limit(RateLimitProperties properties) { return properties.sseLimit(); }
        };

        abstract int limit(RateLimitProperties properties);
    }
}
