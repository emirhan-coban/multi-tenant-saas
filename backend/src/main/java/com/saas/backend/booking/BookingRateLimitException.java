package com.saas.backend.booking;

public class BookingRateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public BookingRateLimitException(long retryAfterSeconds) {
        super("Çok fazla rezervasyon talebi gönderildi. Lütfen daha sonra tekrar deneyin.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
