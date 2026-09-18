package com.saas.backend.booking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingContractTests {
    @Test
    void publicEnumValuesStayStable() {
        assertArrayEquals(new String[]{"NEW", "TRUSTED", "REVIEW_REQUIRED", "BLOCKED"}, names(CustomerTrustState.values()));
        assertArrayEquals(new String[]{"PENDING_APPROVAL", "AUTO_CONFIRMED", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED"}, names(BookingRequestStatus.values()));
        assertArrayEquals(new String[]{"REQUIRE_APPROVAL", "AUTO_CONFIRM", "REJECT"}, names(BookingDecision.values()));
        assertArrayEquals(new String[]{"WEB_LIST", "WEB_3D"}, names(BookingSource.values()));
    }

    @Test
    void abuseAndSchedulingDefaultsStayStable() {
        assertEquals(30, BookingDefaults.BOOKING_HORIZON_DAYS);
        assertEquals(60, BookingDefaults.MINIMUM_LEAD_MINUTES);
        assertEquals(15, BookingDefaults.APPROVAL_HOLD_MINUTES);
        assertEquals(120, BookingDefaults.CUSTOMER_CANCELLATION_MINUTES);
        assertEquals(1, BookingDefaults.MAX_ACTIVE_REQUESTS_PER_CUSTOMER);
        assertEquals(5, BookingDefaults.MAX_REQUESTS_PER_IP_PER_HOUR);
    }

    private String[] names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
