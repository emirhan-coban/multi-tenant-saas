package com.saas.backend.booking;

import com.saas.backend.customer.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/customer/v1/notifications")
public class CustomerNotificationController {
    private final InAppNotificationService service;
    public CustomerNotificationController(InAppNotificationService service) { this.service = service; }
    @GetMapping public List<InAppNotificationService.Notification> list(@AuthenticationPrincipal CustomerPrincipal customer) { return service.customer(customer.customerId()); }
    @PostMapping("/{id}/read") public InAppNotificationService.Notification read(@AuthenticationPrincipal CustomerPrincipal customer,
                                                                                   @PathVariable UUID id) { return service.readCustomer(customer.customerId(), id); }
}
