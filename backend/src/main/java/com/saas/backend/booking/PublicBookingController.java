package com.saas.backend.booking;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/v1/shops")
public class PublicBookingController {
    private final BookingService service;

    public PublicBookingController(BookingService service) {
        this.service = service;
    }

    @GetMapping("/{tenantSlug}")
    public BookingDtos.PublicShop shop(@PathVariable String tenantSlug) {
        return service.shop(tenantSlug);
    }

    @GetMapping("/{tenantSlug}/availability")
    public BookingDtos.Availability availability(@PathVariable String tenantSlug,
                                                  @RequestParam UUID serviceId,
                                                  @RequestParam UUID staffId,
                                                  @RequestParam LocalDate date) {
        return service.availability(tenantSlug, serviceId, staffId, date);
    }
}
