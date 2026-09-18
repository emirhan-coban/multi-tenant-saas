package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reschedule-requests")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class StaffRescheduleController {
    private final StaffRescheduleService service;
    public StaffRescheduleController(StaffRescheduleService service) { this.service = service; }
    @GetMapping public List<BookingDtos.StaffReschedule> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                              @RequestParam(required = false) String status) { return service.list(user, status); }
    @PostMapping("/{id}/approve") public BookingDtos.StaffReschedule approve(@AuthenticationPrincipal AuthenticatedUser user,
                                                                              @PathVariable UUID id,
                                                                              @RequestHeader("Idempotency-Key") String key) { return service.approve(user, id, key); }
    @PostMapping("/{id}/reject") public BookingDtos.StaffReschedule reject(@AuthenticationPrincipal AuthenticatedUser user,
                                                                            @PathVariable UUID id,
                                                                            @RequestHeader("Idempotency-Key") String key,
                                                                            @Valid @RequestBody BookingDtos.RejectRequest body) { return service.reject(user, id, key, body.reason()); }
}
