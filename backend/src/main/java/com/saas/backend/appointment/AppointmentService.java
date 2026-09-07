package com.saas.backend.appointment;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.hizmet.Hizmet;
import com.saas.backend.personel.Personel;
import com.saas.backend.calismasaati.CalismaSaati;
import com.saas.backend.izinkaydi.IzinKaydi;
import com.saas.backend.tenant.Tenant;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.saas.backend.appointment.MusaitlikHesaplayici.ZamanAraligi;

@Service
public class AppointmentService {
    private final AppointmentRepository appointments;
    private final EntityManager em;
    private final ObjectMapper json;
    private final MusaitlikHesaplayici calculator = new MusaitlikHesaplayici();
    public AppointmentService(AppointmentRepository appointments, EntityManager em, ObjectMapper json) {
        this.appointments=appointments; this.em=em; this.json=json;
    }

    // Set RLS context after the transaction begins, on the actual JPA connection.
    private void context(AuthenticatedUser user) {
        em.createNativeQuery("select set_config('app.current_tenant', :tenant, true)")
            .setParameter("tenant",user.tenantId().toString()).getSingleResult();
    }

    @Transactional
    public String create(AuthenticatedUser user, String key, AppointmentDtos.Create request) {
        context(user);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ApiException("INVALID_KEY","Idempotency-Key 1–128 harf, rakam veya . _ : - içermeli");
        String payload=json.writeValueAsString(request);
        String hash=hash(payload);
        // Lock lives until commit; a concurrent duplicate waits and then reads the committed response.
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
            .setParameter("scope",user.tenantId()+":"+user.userId()+":"+key).getSingleResult();
        var previous=em.createNativeQuery("select request_hash,response from idempotency_keys where tenant_id=:tenant and user_id=:user and key=:key")
            .setParameter("tenant",user.tenantId()).setParameter("user",user.userId()).setParameter("key",key).getResultList();
        if (!previous.isEmpty()) {
            Object[] row=(Object[]) previous.getFirst();
            if (!hash.equals(row[0])) throw new ApiException("IDEMPOTENCY_CONFLICT","Bu istek anahtarı farklı bilgilerle kullanılmış");
            return (String) row[1];
        }
        var person=person(user,request.staffId());
        var service=service(user,request.serviceId());
        var a=new Appointment();
        a.setTenantId(user.tenantId()); a.setStaffId(person.getId()); a.setServiceId(service.getId());
        a.setDurationMin(service.getDurationMin()); a.setBufferMin(service.getBufferMin());
        validateSlot(user,a,request.startsAt());
        times(a,request.startsAt());
        a.setCustomerName(request.customerName()); a.setCustomerPhone(request.customerPhone()); a.setNotes(request.notes());
        appointments.saveAndFlush(a);
        String result=json.writeValueAsString(AppointmentDtos.response(a));
        em.createNativeQuery("insert into idempotency_keys(tenant_id,user_id,key,request_hash,response) values (:tenant,:user,:key,:hash,:response)")
            .setParameter("tenant",user.tenantId()).setParameter("user",user.userId()).setParameter("key",key)
            .setParameter("hash",hash).setParameter("response",result).executeUpdate();
        return result;
    }

    @Transactional
    public AppointmentDtos.Response patch(AuthenticatedUser user, UUID id, AppointmentDtos.Patch request) {
        context(user);
        var a=appointments.findByIdAndTenantId(id,user.tenantId()).orElseThrow(() -> missing("Randevu"));
        if (!Objects.equals(a.getVersion(),request.version()))
            throw new ApiException("STALE_VERSION","Randevu başka biri tarafından değiştirildi. Listeyi yenileyin.");
        if (a.getStatus()!=Appointment.Status.CONFIRMED)
            throw new ApiException("INVALID_TRANSITION","Sonuçlanmış veya iptal edilmiş randevu değiştirilemez");
        if (request.startsAt()!=null) {
            if (request.status()!=null && request.status()!=Appointment.Status.CONFIRMED)
                throw new ApiException("INVALID_TRANSITION","Taşıma ve sonuçlandırmayı ayrı işlemlerle yapın");
            person(user,a.getStaffId()); service(user,a.getServiceId());
            validateSlot(user,a,request.startsAt());
            times(a,request.startsAt());
        }
        if (request.status()!=null) {
            if ((request.status()==Appointment.Status.COMPLETED || request.status()==Appointment.Status.NO_SHOW)
                    && a.getStartsAt().isAfter(Instant.now()))
                throw new ApiException("INVALID_TRANSITION","Gelecekteki randevu henüz sonuçlandırılamaz");
            a.setStatus(request.status());
        }
        if (request.notes()!=null) a.setNotes(request.notes());
        appointments.flush(); // @Version catches a second writer even if both read the same version.
        return AppointmentDtos.response(a);
    }

    @Transactional(readOnly=true)
    public List<AppointmentDtos.Response> list(AuthenticatedUser user, UUID staff, Instant from, Instant to) {
        context(user);
        if (from==null || to==null || !from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(93))>0)
            throw new ApiException("INVALID_RANGE","Tarih aralığı en fazla 93 gün olmalı");
        if (staff!=null) person(user,staff);
        return appointments.list(user.tenantId(),staff,from,to).stream().map(AppointmentDtos::response).toList();
    }

    @Transactional(readOnly=true)
    public AppointmentDtos.Availability availability(AuthenticatedUser user, UUID staff, UUID service, LocalDate date) {
        return availability(user,staff,service,date,null);
    }

    @Transactional(readOnly=true)
    public AppointmentDtos.Availability availability(AuthenticatedUser user, UUID staff, UUID service, LocalDate date, UUID appointmentId) {
        context(user);
        person(user,staff);
        var s=service(user,service);
        int duration=s.getDurationMin(), buffer=s.getBufferMin();
        if (appointmentId!=null) {
            var a=appointments.findByIdAndTenantId(appointmentId,user.tenantId()).orElseThrow(()->missing("Randevu"));
            if (!a.getStaffId().equals(staff) || !a.getServiceId().equals(service) || a.getStatus()!=Appointment.Status.CONFIRMED)
                throw new ApiException("INVALID_RANGE","Taşınacak randevu ile personel/hizmet eşleşmeli");
            duration=a.getDurationMin(); buffer=a.getBufferMin();
        }
        ZoneId zone=zone(user);
        return new AppointmentDtos.Availability(zone.getId(),date,
            slots(user,staff,date,zone,duration,buffer,appointmentId).stream()
                .filter(slot -> slot.startsAt().isAfter(Instant.now())).toList());
    }

    private void validateSlot(AuthenticatedUser user, Appointment a, Instant start) {
        if (start==null || !start.isAfter(Instant.now()))
            throw new ApiException("INVALID_RANGE","Randevu gelecekte bir saatte başlamalı");
        var zone=zone(user);
        var available=slots(user,a.getStaffId(),start.atZone(zone).toLocalDate(),zone,
            a.getDurationMin(),a.getBufferMin(),a.getId());
        if (available.stream().noneMatch(slot -> slot.startsAt().equals(start)))
            throw new ApiException("SLOT_UNAVAILABLE","Bu saat artık müsait değil. Müsait saatleri yenileyin.");
    }

    private List<ZamanAraligi> slots(AuthenticatedUser user, UUID staff, LocalDate date, ZoneId zone,
                                    int duration, int buffer, UUID exclude) {
        if (duration<=0 || buffer<0) throw new ApiException("INVALID_DURATION","Hizmet süresi geçersiz");
        var from=date.atStartOfDay(zone).toInstant();
        var to=date.plusDays(1).atStartOfDay(zone).toInstant();
        // Existing weekly API uses 0=Monday ... 6=Sunday.
        var hours=em.createQuery("select h from CalismaSaati h where h.tenant.id=:tenant and h.personel.id=:staff and h.dayOfWeek=:day",CalismaSaati.class)
            .setParameter("tenant",user.tenantId()).setParameter("staff",staff).setParameter("day",date.getDayOfWeek().getValue()-1)
            .getResultList().stream().flatMap(h -> {
                var begin=date.atTime(h.getStartTime()).atZone(zone).toInstant();
                var end=date.atTime(h.getEndTime()).atZone(zone).toInstant();
                // A DST spring gap can collapse an entire local working interval.
                return begin.isBefore(end) ? java.util.stream.Stream.of(new ZamanAraligi(begin,end)) : java.util.stream.Stream.<ZamanAraligi>empty();
            }).toList();
        var leave=em.createQuery("select t from IzinKaydi t where t.tenant.id=:tenant and t.personel.id=:staff and t.startsAt<:to and t.endsAt>:from",IzinKaydi.class)
            .setParameter("tenant",user.tenantId()).setParameter("staff",staff).setParameter("from",from).setParameter("to",to)
            .getResultList().stream().map(t -> new ZamanAraligi(t.getStartsAt(),t.getEndsAt())).toList();
        var busy=appointments.busy(user.tenantId(),staff,from,to).stream().filter(a -> !a.getId().equals(exclude))
            .map(a -> new ZamanAraligi(a.getStartsAt(),a.getBusyUntil())).toList();
        return calculator.hesapla(hours,leave,busy,duration,buffer);
    }

    private Personel person(AuthenticatedUser user, UUID id) {
        return em.createQuery("select p from Personel p where p.id=:id and p.tenant.id=:tenant and p.active=true",Personel.class)
            .setParameter("id",id).setParameter("tenant",user.tenantId()).getResultStream().findFirst().orElseThrow(() -> missing("Personel"));
    }
    private Hizmet service(AuthenticatedUser user, UUID id) {
        return em.createQuery("select s from Hizmet s where s.id=:id and s.tenant.id=:tenant and s.deletedAt is null",Hizmet.class)
            .setParameter("id",id).setParameter("tenant",user.tenantId()).getResultStream().findFirst().orElseThrow(() -> missing("Hizmet"));
    }
    private ZoneId zone(AuthenticatedUser user) {
        var tenant=em.find(Tenant.class,user.tenantId());
        if (tenant==null) throw missing("İşletme");
        String name=tenant.getTimezone();
        try { return ZoneId.of("Istanbul".equals(name) ? "Europe/Istanbul" : name); }
        catch (DateTimeException ex) { throw new ApiException("INVALID_TIMEZONE","İşletmenin saat dilimi geçersiz"); }
    }
    private void times(Appointment a, Instant start) {
        a.setStartsAt(start); a.setEndsAt(start.plusSeconds(a.getDurationMin()*60L));
        a.setBusyUntil(a.getEndsAt().plusSeconds(a.getBufferMin()*60L));
    }
    private static ApiException missing(String name) { return new ApiException("NOT_FOUND",name+" bulunamadı"); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
