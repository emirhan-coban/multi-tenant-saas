package com.saas.backend.booking;

public final class BookingDefaults {
    public static final int BOOKING_HORIZON_DAYS = 30;
    public static final int MINIMUM_LEAD_MINUTES = 60;
    public static final int APPROVAL_HOLD_MINUTES = 15;
    public static final int CUSTOMER_CANCELLATION_MINUTES = 120;
    public static final int MAX_ACTIVE_REQUESTS_PER_CUSTOMER = 1;
    public static final int MAX_REQUESTS_PER_IP_PER_HOUR = 5;
    public static final int MAX_FUTURE_APPOINTMENTS = 2;
    public static final int MAX_DAILY_BOOKINGS = 3;

    private BookingDefaults() {
    }
}
