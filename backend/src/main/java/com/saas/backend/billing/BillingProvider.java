package com.saas.backend.billing;

import java.math.BigDecimal;
import java.time.Instant;

public interface BillingProvider {
    record Customer(String name, String surname, String email, String gsmNumber, String identityNumber,
                    String address, String city, String country, String zipCode) {}
    record Checkout(String token, String htmlContent, int expiresInSeconds) {}
    record CheckoutResult(String conversationId, String subscriptionReference, String customerReference,
                          String pricingPlanReference, String status, Instant trialEndsAt, Instant periodEndsAt) {}
    record Plan(String reference, BigDecimal price, String currency, int trialDays, String interval) {}

    boolean configured();
    Checkout initialize(String conversationId, Customer customer);
    CheckoutResult retrieve(String token, String conversationId);
    Plan plan();
    void cancel(String subscriptionReference);
    boolean validWebhookSignature(BillingDtos.Webhook event, String actual);
}
