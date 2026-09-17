package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/calendar-closures")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class CalendarClosureController {
    private final CalendarClosureService service;
    public CalendarClosureController(CalendarClosureService service) { this.service = service; }

    @GetMapping
    public List<CalendarClosureService.Closure> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @RequestParam Instant from, @RequestParam Instant to) {
        return service.list(user, from, to);
    }
    @PostMapping("/preview")
    public CalendarClosureService.Preview preview(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody CalendarClosureService.Request request) {
        return service.preview(user, request);
    }
    @PostMapping
    public ResponseEntity<CalendarClosureService.Closure> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                                 @Valid @RequestBody CalendarClosureService.Request request) {
        return ResponseEntity.status(201).body(service.create(user, request));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                       @RequestParam int version) {
        service.delete(user, id, version);
        return ResponseEntity.noContent().build();
    }
}
