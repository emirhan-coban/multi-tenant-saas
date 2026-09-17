package com.saas.backend.booking;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.operations.OperationsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingLinkService {
    private final JdbcTemplate db;
    private final OperationsService operations;

    public BookingLinkService(JdbcTemplate db, OperationsService operations) {
        this.db = db;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public BookingLinkResponse get(AuthenticatedUser user) {
        operations.context(user.tenantId());
        var rows = db.query("""
                select id, name, slug, timezone
                from tenants
                where id = ?
                """, (result, row) -> new BookingLinkResponse(
                result.getObject("id", java.util.UUID.class),
                result.getString("name"),
                result.getString("slug"),
                normalizeTimezone(result.getString("timezone"))), user.tenantId());
        if (rows.isEmpty()) throw new ApiException("NOT_FOUND", "İşletme bulunamadı");
        return rows.getFirst();
    }

    private String normalizeTimezone(String timezone) {
        return "Istanbul".equals(timezone) ? "Europe/Istanbul" : timezone;
    }
}
