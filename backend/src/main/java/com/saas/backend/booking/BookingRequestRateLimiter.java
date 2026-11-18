package com.saas.backend.booking;

import org.springframework.stereotype.Component;
import com.saas.backend.operations.TenantRateLimiter;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Component
public class BookingRequestRateLimiter {
    private final TenantRateLimiter limiter;

    public BookingRequestRateLimiter(TenantRateLimiter limiter) {
        this.limiter = limiter;
    }

    public void check(UUID tenant, String address) {
        final io.github.bucket4j.ConsumptionProbe probe;
        try {
            probe = limiter.consumeBooking(tenant, address);
        } catch (RuntimeException ex) {
            throw new BookingRateLimitUnavailableException(ex);
        }
        if (!probe.isConsumed())
            throw new BookingRateLimitException(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
    }

    public void checkAccount(UUID tenant, UUID account) {
        final io.github.bucket4j.ConsumptionProbe probe;
        try {
            probe = limiter.consumeBookingAccount(tenant, account);
        } catch (RuntimeException ex) {
            throw new BookingRateLimitUnavailableException(ex);
        }
        if (!probe.isConsumed())
            throw new BookingRateLimitException(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
    }
}
