package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/booking-link")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
public class BookingLinkController {
    private final BookingLinkService service;

    public BookingLinkController(BookingLinkService service) { this.service = service; }

    @GetMapping
    public BookingLinkResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(user);
    }
}

record BookingLinkResponse(UUID tenantId, String tenantName, String tenantSlug, String timezone) {}
