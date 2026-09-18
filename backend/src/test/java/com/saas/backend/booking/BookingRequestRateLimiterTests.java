package com.saas.backend.booking;

import org.junit.jupiter.api.Test;
import com.saas.backend.operations.TenantRateLimiter;
import io.github.bucket4j.ConsumptionProbe;

import static org.junit.jupiter.api.Assertions.*;

class BookingRequestRateLimiterTests {
    @Test
    void returnsRetryAfterWhenDistributedBucketIsEmpty() {
        var distributed = org.mockito.Mockito.mock(TenantRateLimiter.class);
        org.mockito.Mockito.when(distributed.consumeBooking("127.0.0.1"))
                .thenReturn(ConsumptionProbe.rejected(5, 2_500_000_000L, 0));
        var limiter = new BookingRequestRateLimiter(distributed);
        var error = assertThrows(BookingRateLimitException.class, () -> limiter.check("127.0.0.1"));
        assertEquals(2, error.getRetryAfterSeconds());
    }
}
