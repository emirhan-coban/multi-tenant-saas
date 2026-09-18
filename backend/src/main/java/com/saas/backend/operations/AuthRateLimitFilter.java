package com.saas.backend.operations;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final TenantRateLimiter limiter;

    public AuthRateLimitFilter(TenantRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return scope(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String scope = scope(request);
        try {
            ConsumptionProbe probe = limiter.consumeAuth(scope, request.getRemoteAddr());
            if (!probe.isConsumed()) {
                reject(response, 429, "AUTH_RATE_LIMIT_EXCEEDED",
                        Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L));
                return;
            }
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Auth rate limit dependency unavailable");
            reject(response, 503, "RATE_LIMIT_UNAVAILABLE", 5);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String scope(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return null;
        return switch (request.getRequestURI()) {
            case "/api/auth/login" -> "staff-login";
            case "/api/auth/register" -> "staff-register";
            case "/api/auth/password/forgot" -> "staff-forgot";
            case "/api/auth/password/reset" -> "staff-reset";
            case "/api/customer/v1/auth/login" -> "customer-login";
            case "/api/customer/v1/auth/register" -> "customer-register";
            case "/api/customer/v1/auth/password/forgot" -> "customer-forgot";
            case "/api/customer/v1/auth/password/reset" -> "customer-reset";
            default -> null;
        };
    }

    private static void reject(HttpServletResponse response, int status, String code, long retryAfter)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code
                + "\",\"message\":\"İstek denetimi geçici olarak sınırlı. Lütfen tekrar deneyin.\"}");
    }
}