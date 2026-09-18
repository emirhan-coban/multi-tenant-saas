package com.saas.backend.booking;

import org.springframework.stereotype.Component;
import com.saas.backend.operations.TenantRateLimiter;
import java.util.concurrent.TimeUnit;

@Component
public class BookingRequestRateLimiter {
    private final TenantRateLimiter limiter;

    public BookingRequestRateLimiter(TenantRateLimiter limiter) {
        this.limiter = limiter;
    }

    public void check(String address) {
        var probe = limiter.consumeBooking(address);
        if (!probe.isConsumed())
            throw new BookingRateLimitException(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
    }
}
