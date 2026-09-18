package com.saas.backend.booking;

import com.saas.backend.customer.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/customer/v1/booking-requests")
public class CustomerBookingController {
    private final BookingService service;
    private final CustomerBookingService customerBookings;
    private final VisitVerificationService visits;

    public CustomerBookingController(BookingService service, CustomerBookingService customerBookings,
                                     VisitVerificationService visits) {
        this.service = service;
        this.customerBookings = customerBookings;
        this.visits = visits;
    }

    @PostMapping
    public ResponseEntity<BookingDtos.Response> create(@AuthenticationPrincipal CustomerPrincipal customer,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody BookingDtos.CreateRequest body,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.create(customer.customerId(), key, body, request));
    }

    @GetMapping("/{requestId}")
    public BookingDtos.Response own(@AuthenticationPrincipal CustomerPrincipal customer, @PathVariable UUID requestId) {
        return service.own(customer.customerId(), requestId);
    }

    @GetMapping
    public BookingDtos.CustomerBookings list(@AuthenticationPrincipal CustomerPrincipal customer) {
        return customerBookings.list(customer.customerId());
    }

    @PostMapping("/{requestId}/cancel")
    public BookingDtos.CustomerBooking cancel(@AuthenticationPrincipal CustomerPrincipal customer,
                                               @PathVariable UUID requestId,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody BookingDtos.CustomerCancel body) {
        return customerBookings.cancel(customer.customerId(), requestId, key, body);
    }

    @GetMapping("/{requestId}/reschedule-availability")
    public BookingDtos.Availability rescheduleAvailability(@AuthenticationPrincipal CustomerPrincipal customer,
                                                            @PathVariable UUID requestId,
                                                            @RequestParam LocalDate date) {
        return customerBookings.rescheduleAvailability(customer.customerId(), requestId, date);
    }

    @PostMapping("/{requestId}/reschedule")
    public BookingDtos.CustomerBooking reschedule(@AuthenticationPrincipal CustomerPrincipal customer,
                                                   @PathVariable UUID requestId,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody BookingDtos.CustomerReschedule body) {
        return customerBookings.reschedule(customer.customerId(), requestId, key, body);
    }

    @GetMapping(value = "/{requestId}/calendar.ics", produces = "text/calendar")
    public ResponseEntity<String> calendar(@AuthenticationPrincipal CustomerPrincipal customer,
                                           @PathVariable UUID requestId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=appointment.ics")
                .body(customerBookings.calendar(customer.customerId(), requestId));
    }

    @PostMapping("/{requestId}/visit-code")
    public VisitVerificationDtos.Code visitCode(@AuthenticationPrincipal CustomerPrincipal customer,
                                                @PathVariable UUID requestId) {
        return visits.issue(customer.customerId(), requestId);
    }
}
