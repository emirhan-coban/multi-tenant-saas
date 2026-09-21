package com.saas.backend.operations;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.appointment.Appointment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.sql.Timestamp;

@Service
@Transactional
public class OperationsService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public OperationsService(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public void context(UUID tenant) {
        db.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenant.toString());
    }
    public String plan(UUID tenant) {
        // Faz 9: RLS gerçekten uygulandığı için okuma öncesi bağlam kurulmalı.
        context(tenant);
        var rows=db.queryForList("select plan from tenants where id=?",String.class,tenant);
        if(rows.isEmpty()) throw new ApiException("NOT_FOUND","İşletme bulunamadı");
        if ("PRO".equals(rows.getFirst())) return "PRO";
        Long paid=db.queryForObject("""
            select count(*) from tenant_subscriptions
            where tenant_id=? and (
              status in ('TRIALING','ACTIVE','CANCEL_AT_PERIOD_END')
              or (status='PAST_DUE' and grace_ends_at>now())
            )
            """,Long.class,tenant);
        return paid!=null && paid>0 ? "PRO" : "FREE";
    }
    public ZoneId zone(UUID tenant) {
        context(tenant);
        var rows=db.queryForList("select timezone from tenants where id=?",String.class,tenant);
        if(rows.isEmpty()) throw new ApiException("NOT_FOUND","İşletme bulunamadı");
        String z=rows.getFirst();
        return ZoneId.of("Istanbul".equals(z) ? "Europe/Istanbul" : z);
    }
    public Map<String,Object> usage(AuthenticatedUser u) {
        context(u.tenantId());
        var now=LocalDate.now(zone(u.tenantId())).withDayOfMonth(1);
        var start=now.atStartOfDay(zone(u.tenantId())).toInstant();
        var end=now.plusMonths(1).atStartOfDay(zone(u.tenantId())).toInstant();
        long staff=db.queryForObject("""
            select count(*) from staff s
            where s.tenant_id=? and (
              s.active or exists (
                select 1 from staff_invitations i
                where i.tenant_id=s.tenant_id and i.staff_id=s.id and i.accepted_at is null
              )
            )
            """,Long.class,u.tenantId());
        long appointments=db.queryForObject("select count(*) from appointments where tenant_id=? and created_at>=? and created_at<?",Long.class,u.tenantId(),Timestamp.from(start),Timestamp.from(end));
        boolean pro="PRO".equals(plan(u.tenantId()));
        Map<String,Object> staffUsage=new LinkedHashMap<>(), appointmentUsage=new LinkedHashMap<>();
        staffUsage.put("used",staff); staffUsage.put("limit",pro?null:2);
        appointmentUsage.put("used",appointments); appointmentUsage.put("limit",pro?null:100);
        return Map.of("plan",pro?"PRO":"FREE","staff",staffUsage,"appointments",appointmentUsage,
            "periodStart",start.toString(),"resetsAt",end.toString(),"timezone",zone(u.tenantId()).getId(),"requestsPerMinute",pro?300:60);
    }
    public void checkQuota(AuthenticatedUser u, String resource) {
        context(u.tenantId());
        // One tenant-wide transaction lock: count and insert cannot race.
        db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"quota:"+u.tenantId());
        var usage=usage(u);
        @SuppressWarnings("unchecked") var item=(Map<String,Object>)usage.get(resource);
        Number limit=(Number)item.get("limit"), used=(Number)item.get("used");
        if(limit!=null && used.longValue()>=limit.longValue())
            throw new ApiException("QUOTA_EXCEEDED",resource.equals("staff")?"Personel kotanız doldu. Planınızı yükseltin.":"Aylık randevu kotanız doldu. Planınızı yükseltin.",
                Map.of("resource",resource,"limit",limit,"used",used,"resetsAt",usage.get("resetsAt")));
    }
    public void checkAppointmentQuota(UUID tenant) {
        context(tenant);
        db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"quota:"+tenant);
        if ("PRO".equals(plan(tenant))) return;
        var zone = zone(tenant);
        var month = LocalDate.now(zone).withDayOfMonth(1);
        var from = month.atStartOfDay(zone).toInstant();
        var to = month.plusMonths(1).atStartOfDay(zone).toInstant();
        long used = db.queryForObject("select count(*) from appointments where tenant_id=? and created_at>=? and created_at<?",
                Long.class, tenant, Timestamp.from(from), Timestamp.from(to));
        if (used >= 100) throw new ApiException("QUOTA_EXCEEDED", "Aylık randevu kotanız doldu. Planınızı yükseltin.",
                Map.of("resource", "appointments", "limit", 100, "used", used, "resetsAt", to.toString()));
    }
    public void audit(AuthenticatedUser u,String action,String type,UUID id,Map<String,?> details) {
        context(u.tenantId());
        db.update("insert into audit_log(tenant_id,actor_id,action,entity_type,entity_id,details) values (?,?,?,?,?,cast(? as jsonb))",
            u.tenantId(),u.userId(),action,type,id,json.writeValueAsString(details));
    }
    public void auditCustomer(UUID tenant,UUID customerAccount,String action,String type,UUID id,Map<String,?> details) {
        context(tenant);
        db.update("insert into audit_log(tenant_id,customer_actor_id,action,entity_type,entity_id,details) values (?,?,?,?,?,cast(? as jsonb))",
            tenant,customerAccount,action,type,id,json.writeValueAsString(details));
    }
    public void appointmentCreated(AuthenticatedUser u, Appointment a) {
        audit(u,"APPOINTMENT_CREATED","appointment",a.getId(),Map.of("startsAt",a.getStartsAt().toString()));
        enqueue(u,a);
    }
    public void enqueue(AuthenticatedUser u, Appointment a) {
        context(u.tenantId());
        db.update("update outbox set status='SKIPPED' where tenant_id=? and appointment_id=? and status='PENDING'",u.tenantId(),a.getId());
        // Creator receives the reminder; preference is rechecked at delivery time.
        db.update("insert into outbox(tenant_id,recipient_id,appointment_id,event_type,payload,available_at) values (?,?,?,'APPOINTMENT_REMINDER',cast(? as jsonb),?)",
            u.tenantId(),u.userId(),a.getId(),json.writeValueAsString(Map.of("startsAt",a.getStartsAt().toString())),
            Timestamp.from(a.getStartsAt().minus(Duration.ofHours(24)).isAfter(Instant.now())?a.getStartsAt().minus(Duration.ofHours(24)):Instant.now()));
    }
    public Map<String,Boolean> preferences(AuthenticatedUser u) {
        context(u.tenantId());
        var values=db.queryForList("select reminders_enabled from notification_preferences where tenant_id=? and user_id=?",Boolean.class,u.tenantId(),u.userId());
        return Map.of("remindersEnabled",values.isEmpty() || values.getFirst());
    }
    public Map<String,Boolean> preferences(AuthenticatedUser u, boolean enabled) {
        context(u.tenantId());
        db.update("insert into notification_preferences(tenant_id,user_id,reminders_enabled) values (?,?,?) on conflict(tenant_id,user_id) do update set reminders_enabled=excluded.reminders_enabled",u.tenantId(),u.userId(),enabled);
        return preferences(u);
    }
    public void deactivate(AuthenticatedUser u,UUID id) {
        context(u.tenantId());
        db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"quota:"+u.tenantId());
        if(db.update("update staff set active=false where tenant_id=? and id=? and active",u.tenantId(),id)!=1)
            throw new ApiException("NOT_FOUND","Aktif personel bulunamadı");
        audit(u,"STAFF_DEACTIVATED","staff",id,Map.of());
    }
    public void range(Instant from,Instant to) {
        if(from==null || to==null || !from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(93))>0)
            throw new ApiException("INVALID_RANGE","Tarih aralığı 1–93 gün arasında olmalı");
    }
    public List<Map<String,Object>> auditLog(AuthenticatedUser u,Instant from,Instant to,UUID actor,int page) {
        context(u.tenantId()); range(from,to);
        if(page<0 || page>10000) throw new ApiException("INVALID_RANGE","Geçersiz sayfa");
        return db.queryForList("""
            select a.id,coalesce(a.actor_id,a.customer_actor_id) as "actorId",coalesce(u.full_name,c.full_name) as "actorName",a.action,a.entity_type as "entityType",
                   a.entity_id as "entityId",a.details::text as details,a.created_at as "createdAt"
            from audit_log a left join users u on u.id=a.actor_id left join customer_accounts c on c.id=a.customer_actor_id
            where a.tenant_id=? and a.created_at>=? and a.created_at<? and (?::uuid is null or coalesce(a.actor_id,a.customer_actor_id)=?)
            order by a.created_at desc,a.id desc limit 50 offset ?
            """,u.tenantId(),Timestamp.from(from),Timestamp.from(to),actor,actor,page*50);
    }
    public List<Map<String,Object>> actors(AuthenticatedUser u) {
        context(u.tenantId());
        return db.queryForList("select distinct coalesce(a.actor_id,a.customer_actor_id) as id,coalesce(u.full_name,c.full_name) as name from audit_log a left join users u on u.id=a.actor_id left join customer_accounts c on c.id=a.customer_actor_id where a.tenant_id=? order by name",u.tenantId());
    }
    public Map<String,Object> summary(AuthenticatedUser u,Instant from,Instant to) {
        context(u.tenantId()); range(from,to);
        var totals=db.queryForMap("""
            select count(*) as "appointmentCount",
            count(*) filter(where status='COMPLETED') as "completedCount",
            coalesce(sum(price_amount) filter(where status='COMPLETED'),0) as revenue,
            count(*) filter(where status='COMPLETED' and price_amount is null) as "unknownPriceCount"
            from appointments where tenant_id=? and starts_at>=? and starts_at<?
            """,u.tenantId(),Timestamp.from(from),Timestamp.from(to));
        var services=db.queryForList("""
            select s.name,count(*) as count from appointments a join services s on s.id=a.service_id and s.tenant_id=a.tenant_id
            where a.tenant_id=? and a.starts_at>=? and a.starts_at<? and a.status<>'CANCELLED'
            group by s.id,s.name order by count(*) desc,s.name
            """,u.tenantId(),Timestamp.from(from),Timestamp.from(to));
        var demand=db.queryForMap("""
            select count(*) as "requestCount",
              count(*) filter(where decision='AUTO_CONFIRM') as "autoConfirmedCount",
              count(*) filter(where status='APPROVED') as "approvedRequestCount",
              count(*) filter(where status='REJECTED') as "rejectedRequestCount",
              count(*) filter(where status='EXPIRED') as "expiredRequestCount",
              count(*) filter(where status='CANCELLED') as "cancelledRequestCount"
            from booking_requests where tenant_id=? and created_at>=? and created_at<?
            """,u.tenantId(),Timestamp.from(from),Timestamp.from(to));
        long noShow=db.queryForObject("select count(*) from appointments where tenant_id=? and starts_at>=? and starts_at<? and status='NO_SHOW'",
            Long.class,u.tenantId(),Timestamp.from(from),Timestamp.from(to));
        totals.putAll(demand);
        totals.put("noShowCount",noShow);
        totals.put("services",services); totals.put("popularService",services.isEmpty()?"—":services.getFirst().get("name"));
        totals.put("timezone",zone(u.tenantId()).getId()); return totals;
    }
    public List<Map<String,Object>> utilization(AuthenticatedUser u,Instant from,Instant to) {
        context(u.tenantId()); range(from,to);
        // Multiranges merge overlaps, subtract leave and clip appointments to actual working time.
        return db.queryForList("""
            with staff_ranges as (
              select s.id,s.display_name,
                coalesce((select range_agg(tstzrange(
                  (d::date+h.start_time) at time zone ?, (d::date+h.end_time) at time zone ?, '[)'))
                  from generate_series(?::date,?::date,interval '1 day') d
                  join working_hours h on h.staff_id=s.id and h.tenant_id=s.tenant_id
                  and h.day_of_week=extract(isodow from d)::int-1
                  where (d::date+h.start_time) at time zone ? < (d::date+h.end_time) at time zone ?), '{}'::tstzmultirange) as work,
                coalesce((select range_agg(tstzrange(t.starts_at,t.ends_at,'[)')) from time_off t
                  where t.staff_id=s.id and t.tenant_id=s.tenant_id and t.starts_at<? and t.ends_at>?), '{}'::tstzmultirange) as leave,
                coalesce((select range_agg(tstzrange(a.starts_at,a.busy_until,'[)')) from appointments a
                  where a.staff_id=s.id and a.tenant_id=s.tenant_id and a.status<>'CANCELLED' and a.starts_at<? and a.busy_until>?), '{}'::tstzmultirange) as busy
              from staff s where s.tenant_id=? and s.active
            ), available as (
              select id,display_name,(work-leave)*tstzmultirange(tstzrange(?::timestamptz,?::timestamptz,'[)')) as capacity,busy from staff_ranges
            ), totals as (
              select id,display_name,
              coalesce((select sum(extract(epoch from (upper(r)-lower(r)))/60) from unnest(capacity) r),0) as minutes,
              coalesce((select sum(extract(epoch from (upper(r)-lower(r)))/60) from unnest(capacity*busy) r),0) as occupied from available
            )
            select id,display_name as name,minutes as "availableMinutes",occupied as "occupiedMinutes",
            case when minutes=0 then 0 else round(occupied/minutes*100,1) end as percent from totals order by display_name
            """,zone(u.tenantId()).getId(),zone(u.tenantId()).getId(),
            from.atZone(zone(u.tenantId())).toLocalDate(),to.atZone(zone(u.tenantId())).toLocalDate(),
            zone(u.tenantId()).getId(),zone(u.tenantId()).getId(),
            Timestamp.from(to),Timestamp.from(from),Timestamp.from(to),Timestamp.from(from),u.tenantId(),Timestamp.from(from),Timestamp.from(to));
    }
}
