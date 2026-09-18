package com.saas.backend.account;

/** Hesap bağlantısını güvenli ve kalıcı teslimat kuyruğuna ekler. */
@FunctionalInterface
public interface AccountMailQueue {
    void enqueue(String recipient, String subject, String body, String token);
}
