package com.saas.backend.booking;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShopSceneController {
    private final ShopSceneService service;

    public ShopSceneController(ShopSceneService service) { this.service = service; }

    @GetMapping("/api/public/v1/shops/{tenantSlug}/scene")
    public ShopSceneService.PublicManifest publicManifest(@PathVariable String tenantSlug) {
        return service.publicManifest(tenantSlug);
    }

    @GetMapping("/api/shop-design")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ShopSceneService.State get(@AuthenticationPrincipal AuthenticatedUser user) { return service.get(user); }

    @PutMapping("/api/shop-design/draft")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ShopSceneService.State save(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody ShopSceneService.Update update) {
        return service.save(user, update);
    }

    @PostMapping("/api/shop-design/publish")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ShopSceneService.State publish(@AuthenticationPrincipal AuthenticatedUser user,
                                          @Valid @RequestBody ShopSceneService.Publish command) {
        return service.publish(user, command);
    }

    @PostMapping("/api/shop-design/rollback")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ShopSceneService.State rollback(@AuthenticationPrincipal AuthenticatedUser user,
                                           @Valid @RequestBody ShopSceneService.Rollback command) {
        return service.rollback(user, command);
    }
}
