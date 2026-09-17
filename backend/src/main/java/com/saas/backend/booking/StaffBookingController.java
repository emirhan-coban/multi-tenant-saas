package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class StaffBookingController {
    private final StaffBookingService service;

    public StaffBookingController(StaffBookingService service) { this.service = service; }

    @GetMapping("/booking-requests")
    public List<BookingDtos.StaffResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestParam(defaultValue = "PENDING_APPROVAL") BookingRequestStatus status) {
        return service.list(user, status);
    }

    @PostMapping("/booking-requests/{requestId}/approve")
    public BookingDtos.StaffResponse approve(@AuthenticationPrincipal AuthenticatedUser user,
                                              @PathVariable UUID requestId,
                                              @RequestHeader("Idempotency-Key") String key) {
        return service.approve(user, requestId, key);
    }

    @PostMapping("/booking-requests/{requestId}/reject")
    public BookingDtos.StaffResponse reject(@AuthenticationPrincipal AuthenticatedUser user,
                                             @PathVariable UUID requestId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody(required = false) BookingDtos.RejectRequest body) {
        return service.reject(user, requestId, key, body == null ? null : body.reason());
    }

    @PatchMapping("/booking-customers/{customerId}/trust")
    public BookingDtos.TrustResponse trust(@AuthenticationPrincipal AuthenticatedUser user,
                                            @PathVariable UUID customerId,
                                            @Valid @RequestBody BookingDtos.TrustUpdate body) {
        return service.updateTrust(user, customerId, body);
    }

    @GetMapping("/booking-customers")
    public List<BookingDtos.CustomerSummary> customers(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.customers(user);
    }

    @GetMapping("/booking-customers/{customerId}/trust-events")
    public List<BookingDtos.TrustEvent> trustEvents(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable UUID customerId) {
        return service.trustEvents(user, customerId);
    }
}
