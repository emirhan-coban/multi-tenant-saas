package com.saas.backend.appointment;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class AppointmentController {
    private final AppointmentService service;
    public AppointmentController(AppointmentService service) { this.service=service; }
    @PostMapping(value="/appointments",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AppointmentDtos.Create body) {
        return ResponseEntity.status(201).body(service.create(user,key,body));
    }
    @GetMapping("/appointments")
    public List<AppointmentDtos.Response> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required=false) UUID staffId, @RequestParam Instant from, @RequestParam Instant to) {
        return service.list(user,staffId,from,to);
    }
    @PatchMapping("/appointments/{id}")
    public AppointmentDtos.Response patch(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody AppointmentDtos.Patch body) {
        return service.patch(user,id,body);
    }
    @GetMapping("/availability")
    public AppointmentDtos.Availability availability(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID staffId, @RequestParam UUID serviceId, @RequestParam LocalDate date,
            @RequestParam(required=false) UUID appointmentId) {
        return service.availability(user,staffId,serviceId,date,appointmentId);
    }
}
