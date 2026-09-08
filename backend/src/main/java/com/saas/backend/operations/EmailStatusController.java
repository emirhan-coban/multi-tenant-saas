package com.saas.backend.operations;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;
@RestController
public class EmailStatusController {
    private final GmailSender sender;
    public EmailStatusController(GmailSender sender) {this.sender=sender;}
    @GetMapping("/api/notifications/email-status") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String,Object> status() {return sender.status();}
}
