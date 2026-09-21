package com.saas.backend.billing;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class BillingDtos {
    private BillingDtos() {}

    public record CheckoutRequest(
            @NotBlank @Size(max=80) String name,
            @NotBlank @Size(max=80) String surname,
            @NotBlank @Email @Size(max=160) String email,
            @NotBlank @Pattern(regexp="^\\+?[1-9][0-9]{9,14}$") String gsmNumber,
            @NotBlank @Pattern(regexp="^[0-9]{10,11}$") String identityNumber,
            @NotBlank @Size(max=300) String address,
            @NotBlank @Size(max=80) String city,
            @NotBlank @Size(max=80) String country,
            @Size(max=20) String zipCode
    ) {}
    public record CheckoutResponse(String checkoutId, String htmlContent, Instant expiresAt) {}
    public record SubscriptionStatus(String planCode, String planName, int amountMinor, String currency,
                                     String status, boolean paidFeaturesEnabled, Instant trialEndsAt,
                                     Instant periodEndsAt, Instant graceEndsAt, boolean cancelAtPeriodEnd) {}
    public record Webhook(
            @NotBlank String orderReferenceCode,
            @NotBlank String customerReferenceCode,
            @NotBlank String subscriptionReferenceCode,
            @NotBlank String iyziReferenceCode,
            @NotBlank String iyziEventType,
            long iyziEventTime
    ) {}
    public record InvoiceReference(@NotBlank @Size(max=160) String reference) {}
}
