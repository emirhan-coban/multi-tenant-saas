package com.saas.backend.operations;

import com.saas.backend.config.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import com.saas.backend.ops.OpsMetrics;
import java.time.Instant;
import java.sql.Timestamp;

import java.util.List;

@RestController
@RequestMapping("/api/onboarding")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class OnboardingController {
    private final JdbcTemplate db;
    private final OpsMetrics metrics;

    public OnboardingController(JdbcTemplate db,OpsMetrics metrics) { this.db = db; this.metrics=metrics; }

    public record Step(String key, String title, String path, boolean complete) {}
    public record Status(int completed, int total, String bookingPath, List<Step> steps) {}

    @GetMapping
    @Transactional
    public Status status(@AuthenticationPrincipal AuthenticatedUser user) {
        db.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, user.tenantId().toString());
        var tenant = db.queryForMap("select name,slug from tenants where id=?", user.tenantId());
        boolean profile = tenant.get("name") != null && !tenant.get("name").toString().isBlank();
        boolean services = count("services", user) > 0;
        boolean staff = countActiveStaff(user) > 0;
        boolean hours = count("working_hours", user) > 0;
        boolean trial = count("appointments", user) > 0;
        String bookingPath = "/book/" + tenant.get("slug");
        var steps = List.of(
                new Step("profile", "İşletme profilini tamamla", "/app/account", profile),
                new Step("service", "İlk hizmet ve fiyatı ekle", "/app/services", services),
                new Step("staff", "Berberlerini ekle (kendin dahil)", "/app/staff", staff),
                new Step("hours", "Çalışma saatlerini belirle", "/app/working-hours", hours),
                new Step("trial", "Deneme randevusu oluştur", "/app/appointments", trial),
                new Step("share", "Rezervasyon bağlantısı ve QR'ı paylaş", "/app/booking-link", trial && hours && services)
        );
        int completed=(int)steps.stream().filter(Step::complete).count();
        if(completed==steps.size()) {
            Timestamp created=db.queryForObject("select created_at from tenants where id=?",Timestamp.class,user.tenantId());
            long duration=Math.max(0,java.time.Duration.between(created.toInstant(),Instant.now()).toSeconds());
            int inserted=db.update("insert into onboarding_events(tenant_id,event_type,duration_seconds) values (?,'COMPLETED',?) on conflict do nothing",user.tenantId(),duration);
            if(inserted==1) metrics.onboardingCompleted(duration);
        }
        return new Status(completed, steps.size(), bookingPath, steps);
    }

    private long count(String table, AuthenticatedUser user) {
        Long value = db.queryForObject("select count(*) from " + table + " where tenant_id=?", Long.class, user.tenantId());
        return value == null ? 0 : value;
    }

    private long countActiveStaff(AuthenticatedUser user) {
        Long value = db.queryForObject("select count(*) from staff where tenant_id=? and active", Long.class, user.tenantId());
        return value == null ? 0 : value;
    }
}
