package com.saas.backend.operations;

import com.saas.backend.appointment.Appointment;
import com.saas.backend.config.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class CustomerEmailService {
    public enum Delivery { SENT, SKIPPED, DEFERRED }
    private final JdbcTemplate db;private final ObjectMapper json;private final GmailSender sender;
    public CustomerEmailService(JdbcTemplate db,ObjectMapper json,GmailSender sender) {this.db=db;this.json=json;this.sender=sender;}

    @Transactional
    public void queue(AuthenticatedUser u,Appointment a,String event) {
        queue(u.tenantId(),u.userId(),a,event);
    }
    @Transactional
    public void queueForCustomer(UUID tenant,Appointment a,String event) {
        db.queryForObject("select set_config('app.current_tenant', ?, true)",String.class,tenant.toString());
        var recipients=db.queryForList("select user_id from staff where tenant_id=? and id=? and user_id is not null",UUID.class,tenant,a.getStaffId());
        if(recipients.isEmpty())return;
        queue(tenant,recipients.getFirst(),a,event);
    }
    private void queue(UUID tenant,UUID recipient,Appointment a,String event) {
        db.queryForObject("select set_config('app.current_tenant', ?, true)",String.class,tenant.toString());
        // Old pending customer messages must not announce an obsolete appointment time.
        db.update("update outbox set status='SKIPPED' where tenant_id=? and appointment_id=? and event_type like 'CUSTOMER_%' and status='PENDING'",tenant,a.getId());
        if(!a.isEmailNotifications() || a.getCustomerEmail()==null || a.getCustomerEmail().isBlank())return;
        var tenantRow=db.queryForMap("select name,timezone from tenants where id=?",tenant);
        String zone=tenantRow.get("timezone").toString();if(zone.equals("Istanbul"))zone="Europe/Istanbul";
        String service=db.queryForObject("select name from services where id=? and tenant_id=?",String.class,a.getServiceId(),tenant);
        var payload=Map.of("startsAt",a.getStartsAt().toString(),"timezone",zone,"tenantName",tenantRow.get("name").toString(),
            "serviceName",service,"customerName",Objects.toString(a.getCustomerName(),""));
        insert(tenant,recipient,a,event,payload,null); // null: veritabanı saati (JVM/DB saat farkı yüzünden "henüz hazır değil" olmasın)
        // Near-term appointments get their confirmation, not a second immediate reminder.
        Instant remindAt=a.getStartsAt().minus(Duration.ofHours(24));
        if(!event.equals("CUSTOMER_CANCELLED") && remindAt.isAfter(Instant.now()))
            insert(tenant,recipient,a,"CUSTOMER_REMINDER",payload,remindAt);
    }
    private void insert(UUID tenant,UUID recipient,Appointment a,String event,Map<String,String> payload,Instant at) {
        db.update("""
            insert into outbox(tenant_id,recipient_id,appointment_id,event_type,payload,available_at,recipient_email)
            values (?,?,?,?,cast(? as jsonb),coalesce(?,clock_timestamp()),?)
            """,tenant,recipient,a.getId(),event,json.writeValueAsString(payload),at==null?null:Timestamp.from(at),a.getCustomerEmail());
    }
    // Runs inside OutboxWorker's transaction and row lock.
    public Delivery deliver(Map<String,Object> event) {
        UUID tenant=(UUID)event.get("tenant_id"),id=(UUID)event.get("appointment_id");
        String type=event.get("event_type").toString(),recipient=(String)event.get("recipient_email");
        var payload=json.readTree(event.get("payload").toString());
        Instant expected=Instant.parse(payload.get("startsAt").asString());
        var rows=db.queryForList("select status,starts_at,customer_email,email_notifications from appointments where tenant_id=? and id=?",tenant,id);
        if(rows.isEmpty())return Delivery.SKIPPED;
        var a=rows.getFirst();
        boolean cancelled=type.equals("CUSTOMER_CANCELLED");
        boolean current=Boolean.TRUE.equals(a.get("email_notifications"))
            && Objects.equals(recipient,a.get("customer_email"))
            && expected.equals(((Timestamp)a.get("starts_at")).toInstant())
            && (cancelled?"CANCELLED".equals(a.get("status")):"CONFIRMED".equals(a.get("status")) && expected.isAfter(Instant.now()));
        if(!current)return Delivery.SKIPPED;
        if(!sender.ready())return Delivery.DEFERRED;
        if(!sender.allowed(recipient))return Delivery.SKIPPED;
        String title=switch(type) {
            case "CUSTOMER_CONFIRMATION" -> "Randevunuz onaylandı";
            case "CUSTOMER_RESCHEDULED" -> "Randevu saatiniz değiştirildi";
            case "CUSTOMER_CANCELLED" -> "Randevunuz iptal edildi";
            case "CUSTOMER_REMINDER" -> "Randevu hatırlatması";
            default -> throw new IllegalArgumentException("Unknown customer event");
        };
        String zone=payload.get("timezone").asString();
        String time=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of(zone)).format(expected);
        String body="Merhaba "+payload.get("customerName").asString()+" HANIM"+",\n\n"+title+".\n\n"
            +"İşletme: "+payload.get("tenantName").asString()+"\nHizmet: "+payload.get("serviceName").asString()
            +"\nRandevu zamanı: "+time+" ("+zone+")\n\n"
            +"Sorularınız veya değişiklik talepleriniz için işletmeyle iletişime geçebilirsiniz.";
        sender.send((UUID)event.get("id"),recipient,title,body);
        return Delivery.SENT;
    }
}
