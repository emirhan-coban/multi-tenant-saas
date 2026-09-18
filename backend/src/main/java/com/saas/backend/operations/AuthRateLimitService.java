package com.saas.backend.operations;

import com.saas.backend.auth.ApiException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AuthRateLimitService {
    private final TenantRateLimiter limiter;

    public AuthRateLimitService(TenantRateLimiter limiter) {
        this.limiter = limiter;
    }

    public void checkAccount(String scope, String email) {
        try {
            var probe = limiter.consumeAuthAccount(scope, normalize(email));
            if (!probe.isConsumed()) {
                throw new ApiException("TOO_MANY_REQUESTS", "Çok fazla hesap isteği gönderdiniz. Lütfen daha sonra tekrar deneyin.");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ApiException("RATE_LIMIT_UNAVAILABLE", "İstek denetimi geçici olarak kullanılamıyor. Tekrar deneyin.");
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}