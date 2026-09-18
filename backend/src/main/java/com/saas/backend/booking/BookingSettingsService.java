package com.saas.backend.booking;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingSettingsService {
    private final EntityManager em;

    public BookingSettingsService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public Settings get(AuthenticatedUser user) {
        context(user);
        return current(user);
    }

    @Transactional
    public Settings update(AuthenticatedUser user, Update update) {
        context(user);
        int changed = em.createNativeQuery("""
                update booking_settings set trusted_auto_confirm_enabled=:trusted,
                  max_future_appointments=:future,max_daily_bookings=:daily,
                  booking_horizon_days=:horizon,minimum_lead_minutes=:lead,
                  request_expiry_minutes=:expiry,cancellation_cutoff_minutes=:cutoff,
                  updated_at=now(),version=version+1
                where tenant_id=:tenant and version=:version
                """).setParameter("trusted", update.trustedAutoConfirmEnabled())
                .setParameter("future", update.maxFutureAppointments())
                .setParameter("daily", update.maxDailyBookings())
                .setParameter("horizon", update.bookingHorizonDays())
                .setParameter("lead", update.minimumLeadMinutes())
                .setParameter("expiry", update.requestExpiryMinutes())
                .setParameter("cutoff", update.cancellationCutoffMinutes())
                .setParameter("tenant", user.tenantId()).setParameter("version", update.version())
                .executeUpdate();
        if (changed != 1) throw new ApiException("STALE_VERSION", "Rezervasyon ayarları başka biri tarafından değiştirildi. Sayfayı yenileyin.");
        return current(user);
    }

    private Settings current(AuthenticatedUser user) {
        Object[] row = (Object[]) em.createNativeQuery("""
                select trusted_auto_confirm_enabled,max_future_appointments,max_daily_bookings,
                       booking_horizon_days,minimum_lead_minutes,request_expiry_minutes,
                       cancellation_cutoff_minutes,version,updated_at
                from booking_settings where tenant_id=:tenant
                """).setParameter("tenant", user.tenantId()).getSingleResult();
        return new Settings((Boolean) row[0], number(row[1]), number(row[2]), number(row[3]),
                number(row[4]), number(row[5]), number(row[6]), number(row[7]), row[8].toString());
    }

    private void context(AuthenticatedUser user) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", user.tenantId().toString()).getSingleResult();
    }

    private static int number(Object value) { return ((Number) value).intValue(); }

    public record Settings(boolean trustedAutoConfirmEnabled, int maxFutureAppointments,
                           int maxDailyBookings, int bookingHorizonDays, int minimumLeadMinutes,
                           int requestExpiryMinutes, int cancellationCutoffMinutes, int version,
                           String updatedAt) {}

    public record Update(@NotNull Boolean trustedAutoConfirmEnabled,
                         @NotNull @Min(0) @Max(20) Integer maxFutureAppointments,
                         @NotNull @Min(1) @Max(50) Integer maxDailyBookings,
                         @NotNull @Min(1) @Max(365) Integer bookingHorizonDays,
                         @NotNull @Min(0) @Max(10080) Integer minimumLeadMinutes,
                         @NotNull @Min(5) @Max(1440) Integer requestExpiryMinutes,
                         @NotNull @Min(0) @Max(10080) Integer cancellationCutoffMinutes,
                         @NotNull @Min(0) Integer version) {}
}
