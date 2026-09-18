package com.saas.backend.booking;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.customer.CustomerAccount;
import com.saas.backend.hizmet.Hizmet;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BookingAppointmentFactory {
    public Appointment create(UUID tenantId, UUID tenantCustomerId, CustomerAccount customer,
                              Hizmet service, BookingRequest booking) {
        var result = new Appointment();
        result.setTenantId(tenantId);
        result.setCustomerId(tenantCustomerId);
        result.setStaffId(booking.getStaffId());
        result.setServiceId(service.getId());
        result.setCustomerName(customer.getFullName());
        result.setCustomerPhone(customer.getPhone());
        result.setCustomerEmail(customer.getEmail());
        result.setEmailNotifications(true);
        result.setSource(booking.getSource().name());
        result.setStartsAt(booking.getStartsAt());
        result.setDurationMin(service.getDurationMin());
        result.setBufferMin(service.getBufferMin());
        result.setEndsAt(booking.getStartsAt().plusSeconds(service.getDurationMin() * 60L));
        result.setBusyUntil(booking.getBusyUntil());
        result.setPriceAmount(service.getPrice());
        result.setNotes(booking.getNote());
        return result;
    }
}
