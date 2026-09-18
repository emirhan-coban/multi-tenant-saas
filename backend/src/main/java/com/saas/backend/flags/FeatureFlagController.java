package com.saas.backend.flags;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.saas.backend.auth.ApiException;

import java.util.List;
import java.util.UUID;

@RestController
public class FeatureFlagController {
    private final FeatureFlagService flags;
    private final JdbcTemplate db;

    public FeatureFlagController(FeatureFlagService flags, JdbcTemplate db) {
        this.flags = flags;
        this.db = db;
    }

    /** İstemcinin ihtiyaç duyduğu, gizli olmayan bayrak özeti. */
    @GetMapping("/api/public/v1/shops/{tenantSlug}/features")
    @Transactional(readOnly = true)
    public PublicFeatures publicFeatures(@PathVariable String tenantSlug) {
        var rows = db.queryForList("select id from resolve_public_tenant(?)", UUID.class, tenantSlug);
        if (rows.isEmpty()) throw new ApiException("NOT_FOUND", "İşletme bulunamadı.");
        var state = flags.resolve(rows.getFirst());
        return new PublicFeatures(state.get(FeatureFlag.BOOKING_3D).enabled(),
                state.get(FeatureFlag.PUSH_NOTIFICATIONS).enabled(), state.get(FeatureFlag.PWA_INSTALL).enabled());
    }

    @GetMapping("/api/feature-flags")
    @PreAuthorize("hasRole('OWNER')")
    public List<FeatureFlagService.State> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return List.copyOf(flags.resolve(user.tenantId()).values());
    }

    /** {@code enabled=null} işletme geçersiz kılmasını kaldırır ve global değere döner. */
    @PutMapping("/api/feature-flags/{key}")
    @PreAuthorize("hasRole('OWNER')")
    public List<FeatureFlagService.State> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String key,
                                                 @Valid @RequestBody Update body) {
        return List.copyOf(flags.setTenantOverride(user, key, body.enabled(), body.reason()).values());
    }

    public record PublicFeatures(boolean threeD, boolean pushNotifications, boolean pwaInstall) {}
    public record Update(Boolean enabled, @NotBlank @Size(max = 250) String reason) {}
}
