package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications/in-app")
public class StaffNotificationController {
    private final InAppNotificationService service;
    public StaffNotificationController(InAppNotificationService service) { this.service = service; }
    @GetMapping public List<InAppNotificationService.Notification> list(@AuthenticationPrincipal AuthenticatedUser user) { return service.staff(user); }
    @PostMapping("/{id}/read") public InAppNotificationService.Notification read(@AuthenticationPrincipal AuthenticatedUser user,
                                                                                   @PathVariable UUID id) { return service.readStaff(user, id); }
}
