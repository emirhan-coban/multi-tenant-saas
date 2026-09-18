package com.saas.backend.push;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.customer.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class PushController {
    private final PushSubscriptionService subscriptions;
    private final PushNotificationService notifications;
    private final WebPushConfiguration.WebPushKeys keys;

    public PushController(PushSubscriptionService subscriptions, PushNotificationService notifications,
                          WebPushConfiguration.WebPushKeys keys) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.keys = keys;
    }

    /** Tarayıcının {@code pushManager.subscribe} için ihtiyaç duyduğu uygulama sunucusu anahtarı (gizli değildir). */
    @GetMapping("/api/public/v1/push/config")
    public Map<String, Object> config() {
        return keys.configured()
                ? Map.of("enabled", true, "publicKey", keys.publicKey())
                : Map.of("enabled", false);
    }

    @PostMapping("/api/customer/v1/push-subscriptions")
    public ResponseEntity<Void> subscribeCustomer(@AuthenticationPrincipal CustomerPrincipal customer,
                                                  @RequestBody PushSubscriptionService.Request body, HttpServletRequest http) {
        subscriptions.register(PushSubscriptionService.OwnerType.CUSTOMER, customer.customerId(), null, body, http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/customer/v1/push-subscriptions/unsubscribe")
    public ResponseEntity<Void> unsubscribeCustomer(@AuthenticationPrincipal CustomerPrincipal customer,
                                                    @RequestBody Unsubscribe body) {
        subscriptions.unregister(PushSubscriptionService.OwnerType.CUSTOMER, customer.customerId(), body.endpoint());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/customer/v1/push-subscriptions/test")
    public Map<String, Integer> testCustomer(@AuthenticationPrincipal CustomerPrincipal customer) {
        return Map.of("queued", notifications.queueTest(PushSubscriptionService.OwnerType.CUSTOMER, customer.customerId(), null));
    }

    @PostMapping("/api/push-subscriptions")
    public ResponseEntity<Void> subscribeStaff(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestBody PushSubscriptionService.Request body, HttpServletRequest http) {
        subscriptions.register(PushSubscriptionService.OwnerType.STAFF, user.userId(), user.tenantId(), body, http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/push-subscriptions/unsubscribe")
    public ResponseEntity<Void> unsubscribeStaff(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Unsubscribe body) {
        subscriptions.unregister(PushSubscriptionService.OwnerType.STAFF, user.userId(), body.endpoint());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/push-subscriptions/test")
    public Map<String, Integer> testStaff(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("queued", notifications.queueTest(PushSubscriptionService.OwnerType.STAFF, user.userId(), user.tenantId()));
    }

    public record Unsubscribe(String endpoint) {}
}
