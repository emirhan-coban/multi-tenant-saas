package com.saas.backend.operations;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service) {this.service=service;}
    @GetMapping("/usage") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String,Object> usage(@AuthenticationPrincipal AuthenticatedUser u) {return service.usage(u);}
    @GetMapping("/audit-log") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String,Object>> audit(@AuthenticationPrincipal AuthenticatedUser u,@RequestParam Instant from,@RequestParam Instant to,
        @RequestParam(required=false) UUID actorId,@RequestParam(defaultValue="0") int page) {return service.auditLog(u,from,to,actorId,page);}
    @GetMapping("/audit-log/actors") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String,Object>> actors(@AuthenticationPrincipal AuthenticatedUser u) {return service.actors(u);}
    @GetMapping("/reports/summary") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String,Object> summary(@AuthenticationPrincipal AuthenticatedUser u,@RequestParam Instant from,@RequestParam Instant to) {return service.summary(u,from,to);}
    @GetMapping("/reports/staff-utilization") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String,Object>> utilization(@AuthenticationPrincipal AuthenticatedUser u,@RequestParam Instant from,@RequestParam Instant to) {return service.utilization(u,from,to);}
    @GetMapping("/notification-preferences")
    public Map<String,Boolean> preferences(@AuthenticationPrincipal AuthenticatedUser u) {return service.preferences(u);}
    public record Preferences(@NotNull Boolean remindersEnabled) {}
    @PatchMapping("/notification-preferences")
    public Map<String,Boolean> preferences(@AuthenticationPrincipal AuthenticatedUser u,@Valid @RequestBody Preferences p) {return service.preferences(u,p.remindersEnabled());}
    @DeleteMapping("/staff/{id}") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void deactivate(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable UUID id) {service.deactivate(u,id);}
}
