package com.saas.backend.booking;

public class BookingRateLimitUnavailableException extends RuntimeException {
    public BookingRateLimitUnavailableException(Throwable cause) {
        super("Rezervasyon isteği denetimi geçici olarak kullanılamıyor.", cause);
    }
}