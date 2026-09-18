package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/booking-settings")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class BookingSettingsController {
    private final BookingSettingsService service;

    public BookingSettingsController(BookingSettingsService service) { this.service = service; }

    @GetMapping
    public BookingSettingsService.Settings get(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(user);
    }

    @PutMapping
    public BookingSettingsService.Settings update(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @Valid @RequestBody BookingSettingsService.Update update) {
        return service.update(user, update);
    }
}
