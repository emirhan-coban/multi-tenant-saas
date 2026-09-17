package com.saas.backend.booking;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BookingHoldExpiryService {
    private final EntityManager em;

    public BookingHoldExpiryService(EntityManager em) { this.em = em; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expire(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
        return em.createNativeQuery("""
                update booking_requests set status='EXPIRED',hold_expires_at=null,updated_at=now(),version=version+1
                where tenant_id=:tenant and status='PENDING_APPROVAL' and hold_expires_at<=now()
                """).setParameter("tenant", tenantId).executeUpdate();
    }
}
