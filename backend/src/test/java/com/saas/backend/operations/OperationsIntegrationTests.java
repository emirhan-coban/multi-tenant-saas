package com.saas.backend.operations;
import com.saas.backend.PostgresTestBase;
import com.saas.backend.auth.*;
import com.saas.backend.config.*;
import com.saas.backend.appointment.*;
import com.saas.backend.personel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsIntegrationTests extends PostgresTestBase {
    @Autowired OperationsService ops;
    @Autowired AppointmentService appointments;
    @Autowired PersonelService staffService;
    @Autowired TenantRateLimiter limiter;
    @Autowired OutboxWorker worker;
    // Faz 9: fixture SQL'i şema sahibiyle çalışır; uygulama kodu kısıtlı saas_app rolündedir.
    final JdbcTemplate db = ownerJdbc();
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager tm;
    @Autowired net.javacrumbs.shedlock.core.LockProvider locks;
    @MockitoSpyBean NotificationSender sender;
    @Value("${local.server.port}") int port;
    UUID tenant,user,staff,service;
    AuthenticatedUser owner;
    Instant start=Instant.parse("2030-01-07T06:00:00Z");
    @BeforeEach void setup() {
        tenant=UUID.randomUUID();user=UUID.randomUUID();staff=UUID.randomUUID();service=UUID.randomUUID();
        db.update("insert into tenants(id,name,slug,timezone) values (?,?,?,'Europe/Istanbul')",tenant,"Phase4",tenant.toString());
        db.update("insert into users(id,email,password_hash,full_name) values (?,?,?,?)",user,user+"@example.test","unused","Tester");
        db.update("insert into staff(id,tenant_id,user_id,display_name) values (?,?,?,?)",staff,tenant,user,"Tester");
        db.update("insert into services(id,tenant_id,name,duration_min,buffer_min,price) values (?,?,?,30,10,120)",service,tenant,"Service");
        db.update("insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (?,?,0,'09:00','18:00')",tenant,staff);
        owner=new AuthenticatedUser(user,tenant,"OWNER");
    }
    AppointmentDtos.Create input(Instant at) {return new AppointmentDtos.Create(staff,service,at,"Customer",null,null);}
    AppointmentDtos.Response create(String key,Instant at) {return json.readValue(appointments.create(owner,key,input(at)),AppointmentDtos.Response.class);}
    int count(String table) {return db.queryForObject("select count(*) from "+table+" where tenant_id=?",Integer.class,tenant);}
    void seed(int count) {
        db.update("""
          insert into appointments(tenant_id,staff_id,service_id,starts_at,ends_at,busy_until,duration_min,buffer_min,status)
          select ?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,30,10,'CANCELLED' from generate_series(1,?)
          """,tenant,staff,service,start.toString(),start.plusSeconds(1800).toString(),start.plusSeconds(2400).toString(),count);
    }
    HttpResponse<String> get(String path,String role) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Authorization","Bearer "+jwt.generateAccessToken(user,tenant,role)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void freeUsageAndProLimits() {
        assertEquals("FREE",ops.usage(owner).get("plan"));
        assertEquals(60,ops.usage(owner).get("requestsPerMinute"));
        db.update("update tenants set plan='PRO' where id=?",tenant);
        assertEquals(300,ops.usage(owner).get("requestsPerMinute"));
        assertNull(((Map<?,?>)ops.usage(owner).get("staff")).get("limit"));
    }
    @Test void fiftyStaffInvitesCannotExceedTwoActiveStaff() throws Exception {
        try(var pool=Executors.newFixedThreadPool(20)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<50;i++) futures.add(pool.submit(()->{gate.await();try {
                staffService.davetEt(owner,new PersonelDtos.DavetEtRequest(UUID.randomUUID()+"@example.test","New"));return true;
            } catch(ApiException e) {assertEquals("QUOTA_EXCEEDED",e.getCode());return false;}}));
            gate.countDown();int success=0;for(var f:futures) if(f.get(30,TimeUnit.SECONDS))success++;
            assertEquals(1,success);
        }
        assertEquals(2,count("staff"));assertEquals(1,count("audit_log"));
    }
    @Test void appointmentQuotaRaceAndRollback() throws Exception {
        seed(99);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<8;i++) {final int slot=i;futures.add(pool.submit(()->{gate.await();try {
                create("quota-"+slot,start.plusSeconds(slot*2400L));return true;
            } catch(ApiException e) {assertEquals("QUOTA_EXCEEDED",e.getCode());return false;}}));}
            gate.countDown();int success=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))success++;
            assertEquals(1,success);
        }
        assertEquals(100,count("appointments"));assertEquals(1,count("outbox"));assertEquals(1,count("audit_log"));
    }
    @Test void quotaErrorContainsLimitAndUsedAndReplayStillWorks() {
        var original=appointments.create(owner,"replay",input(start));seed(99);
        var ex=assertThrows(ApiException.class,()->create("over",start.plusSeconds(2400)));
        assertEquals(100,((Number)ex.getDetails().get("used")).intValue());
        assertEquals(100,((Number)ex.getDetails().get("limit")).intValue());
        assertEquals(original,appointments.create(owner,"replay",input(start)));
        assertEquals(1,count("outbox"));
    }
    @Test void olderMonthDoesNotUseQuota() {
        seed(100);db.update("update appointments set created_at=now()-interval '2 months' where tenant_id=?",tenant);
        create("new-month",start);assertEquals(101,count("appointments"));
    }
    @Test void deactivateFreesQuotaAndIsAudited() {
        ops.deactivate(owner,staff);
        assertEquals(0,((Number)((Map<?,?>)ops.usage(owner).get("staff")).get("used")).intValue());
        assertEquals("STAFF_DEACTIVATED",db.queryForObject("select action from audit_log where tenant_id=?",String.class,tenant));
    }
    @Test void redisAtomicCounterAcrossParallelConsumers() throws Exception {
        try(var pool=Executors.newFixedThreadPool(20)) {
            var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<100;i++)tasks.add(pool.submit(()->limiter.consume(tenant,false).isConsumed()));
            int ok=0;for(var f:tasks)if(f.get(20,TimeUnit.SECONDS))ok++;
            assertEquals(60,ok);
        }
        assertFalse(limiter.consume(tenant,false).isConsumed());
        assertTrue(limiter.consume(UUID.randomUUID(),false).isConsumed());
    }
    @Test void proAllowsThreeHundredRequests() {
        for(int i=0;i<300;i++)assertTrue(limiter.consume(tenant,true).isConsumed());
        assertFalse(limiter.consume(tenant,true).isConsumed());
    }
    @Test void proCanCreateBeyondMonthlyFreeQuota() {
        seed(100);db.update("update tenants set plan='PRO' where id=?",tenant);
        create("pro",start);assertEquals(101,count("appointments"));
    }
    @Test void unavailableRedisFailsClosedWithRetryHeader() throws Exception {
        var failed=mock(TenantRateLimiter.class);
        when(failed.consume(tenant,false)).thenThrow(new IllegalStateException("unavailable"));
        var filter=new RateLimitFilter(failed,ops,new com.saas.backend.ops.OpsMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner,null,List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        try {
            filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET","/api/usage"),response,
                (a,b)->fail("Dependency failure must not bypass rate control"));
            assertEquals(503,response.getStatus());assertEquals("5",response.getHeader("Retry-After"));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void http429IncludesRetryAfterAndTrace() throws Exception {
        for(int i=0;i<60;i++)limiter.consume(tenant,false);
        var response=get("/api/usage","OWNER");
        assertEquals(429,response.statusCode());assertTrue(response.body().contains("RATE_LIMIT_EXCEEDED"));
        assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())>0);
        assertDoesNotThrow(()->UUID.fromString(response.headers().firstValue("X-Trace-Id").orElseThrow()));
    }
    @Test void staffCannotReadManagementReports() throws Exception {
        assertEquals(403,get("/api/usage","STAFF").statusCode());
        assertEquals(403,get("/api/audit-log/actors","STAFF").statusCode());
        assertEquals(200,get("/api/notification-preferences","STAFF").statusCode());
    }
    @Test void auditImmutableAndTenantFiltered() {
        ops.audit(owner,"TEST","staff",staff,Map.of("text","quote\" newline\n"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("delete from audit_log where tenant_id=?",tenant));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update audit_log set action='EDIT' where tenant_id=?",tenant));
        assertEquals(1,ops.auditLog(owner,Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),user,0).size());
        assertTrue(ops.auditLog(owner,Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),UUID.randomUUID(),0).isEmpty());
        assertTrue(ops.auditLog(new AuthenticatedUser(user,UUID.randomUUID(),"OWNER"),Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),null,0).isEmpty());
    }
    @Test void transactionRollbackRemovesAuditAndOutbox() {
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(tm).execute(status->{
            create("rollback",start);throw new IllegalStateException("Rollback");
        }));
        assertEquals(0,count("appointments"));assertEquals(0,count("audit_log"));assertEquals(0,count("outbox"));
    }
    void due() {db.update("update outbox set available_at=now()-interval '1 minute' where tenant_id=?",tenant);}
    String state() {return db.queryForObject("select status from outbox where tenant_id=? order by created_at desc limit 1",String.class,tenant);}
    @Test void outboxDeliversOnceOnRepeatedRuns() {
        create("send",start);due();worker.process(tenant);worker.process(tenant);
        assertEquals("SENT",state());verify(sender,times(1)).send(any(),eq(tenant),eq(user),any());
    }
    @Test void parallelWorkersCannotDeliverTheSameRow() throws Exception {
        create("parallel",start);due();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->worker.process(tenant));var second=pool.submit(()->worker.process(tenant));
            first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
        }
        assertEquals("SENT",state());verify(sender,times(1)).send(any(),eq(tenant),any(),any());
    }
    @Test void notificationOptOutSkipsQueuedEvent() {
        create("optout",start);ops.preferences(owner,false);due();worker.process(tenant);
        assertEquals("SKIPPED",state());verify(sender,never()).send(any(),eq(tenant),any(),any());
    }
    @Test void cancelledAppointmentNeverSendsReminder() {
        var a=create("cancel",start);appointments.patch(owner,a.id(),new AppointmentDtos.Patch(0,null,Appointment.Status.CANCELLED,null));
        due();worker.process(tenant);assertEquals("SKIPPED",state());
    }
    @Test void rescheduleInvalidatesOldReminder() {
        var a=create("move",start);appointments.patch(owner,a.id(),new AppointmentDtos.Patch(0,start.plusSeconds(2400),null,null));
        assertEquals(1,db.queryForObject("select count(*) from outbox where tenant_id=? and status='SKIPPED'",Integer.class,tenant));
        due();worker.process(tenant);verify(sender,times(1)).send(any(),eq(tenant),any(),any());
    }
    @Test void sendFailureRetriesWithBackoffAndStopsAtFive() {
        create("retry",start);doThrow(new IllegalStateException("provider")).when(sender).send(any(),eq(tenant),any(),any());
        for(int i=0;i<5;i++) {due();worker.process(tenant);}
        assertEquals("FAILED",state());
        worker.process(tenant);verify(sender,times(5)).send(any(),eq(tenant),any(),any());
    }
    @Test void reportPreservesPriceAndExcludesUnknownAndCancelledRevenue() {
        var a=create("price",start);
        db.update("update services set price=999 where id=?",service);
        db.update("update appointments set status='COMPLETED' where id=?",a.id());
        var data=ops.summary(owner,start,start.plusSeconds(86400));
        assertEquals(120,((Number)data.get("revenue")).intValue());assertEquals("Service",data.get("popularService"));
        seed(1);assertEquals(2,((Number)ops.summary(owner,start,start.plusSeconds(86400)).get("appointmentCount")).intValue());
        db.update("update appointments set status='COMPLETED',starts_at=starts_at+interval '1 hour',ends_at=ends_at+interval '1 hour',busy_until=busy_until+interval '1 hour' where tenant_id=? and price_amount is null",tenant);
        var unknown=ops.summary(owner,start,start.plusSeconds(86400));
        assertEquals(1,((Number)unknown.get("unknownPriceCount")).intValue());
        assertEquals(120,((Number)unknown.get("revenue")).intValue());
    }
    @Test void utilizationSubtractsLeaveAndIncludesBuffer() {
        create("capacity",start);
        db.update("insert into time_off(tenant_id,staff_id,starts_at,ends_at) values (?,?,?::timestamptz,?::timestamptz)",tenant,staff,start.plusSeconds(3600).toString(),start.plusSeconds(7200).toString());
        var row=ops.utilization(owner,start,start.plusSeconds(86400)).getFirst();
        assertEquals(480,((Number)row.get("availableMinutes")).intValue());
        assertEquals(40,((Number)row.get("occupiedMinutes")).intValue());
    }
    @Test void reportsRejectExcessiveRanges() {
        assertThrows(ApiException.class,()->ops.summary(owner,start,start.plus(Duration.ofDays(94))));
        assertThrows(ApiException.class,()->ops.utilization(owner,start,start));
    }
    @Test void shedlockOnlyOneOwner() {
        var config=new net.javacrumbs.shedlock.core.LockConfiguration(Instant.now(),"test-"+tenant,Duration.ofSeconds(30),Duration.ZERO);
        var lock=locks.lock(config).orElseThrow();
        assertTrue(locks.lock(config).isEmpty());lock.unlock();
    }

    @Test void priceEditThroughHttpCreatesAuditRecord() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/services/"+service))
            .header("Authorization","Bearer "+jwt.generateAccessToken(user,tenant,"OWNER"))
            .header("Content-Type","application/json")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"name\":\"Service\",\"durationMin\":30,\"bufferMin\":10,\"price\":250}")).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("SERVICE_PRICE_CHANGED",db.queryForObject("select action from audit_log where tenant_id=?",String.class,tenant));
        assertEquals(120,db.queryForObject("select (details->>'before')::numeric from audit_log where tenant_id=?",Integer.class,tenant));
    }
    @Test void httpQuotaIs403WithStructuredDetails() throws Exception {
        seed(100);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/appointments"))
            .header("Authorization","Bearer "+jwt.generateAccessToken(user,tenant,"OWNER"))
            .header("Content-Type","application/json").header("Idempotency-Key","quota-http")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input(start)))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(403,response.statusCode());var body=json.readTree(response.body());
        assertEquals("QUOTA_EXCEEDED",body.get("code").asString());assertEquals(100,body.get("details").get("limit").asInt());
    }
    @Test void newTablesEnforceRlsForNonSuperuser() throws Exception {
        create("rls",start);ops.preferences(owner,false);
        String role="p4_"+UUID.randomUUID().toString().replace("-","");
        db.execute("create role "+role);db.execute("grant usage on schema public to "+role);
        db.execute("grant select on audit_log,outbox,notification_preferences to "+role);
        try(var c=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                s.execute("set local role "+role);
                for(var table:List.of("audit_log","outbox","notification_preferences")) {
                    s.execute("select set_config('app.current_tenant','"+UUID.randomUUID()+"',true)");
                    try(var rows=s.executeQuery("select count(*) from "+table)) {rows.next();assertEquals(0,rows.getInt(1));}
                    s.execute("select set_config('app.current_tenant','"+tenant+"',true)");
                    try(var rows=s.executeQuery("select count(*) from "+table)) {rows.next();assertEquals(1,rows.getInt(1));}
                }
            } finally {c.rollback();}
        }
    }
    @Test void daylightSavingCollapsedIntervalHasZeroCapacity() {
        db.update("update tenants set timezone='Europe/Berlin' where id=?",tenant);
        db.update("update working_hours set day_of_week=6,start_time='02:45',end_time='03:15' where staff_id=?",staff);
        var row=ops.utilization(owner,Instant.parse("2030-03-31T00:00:00Z"),Instant.parse("2030-04-01T00:00:00Z")).getFirst();
        assertEquals(0,((Number)row.get("availableMinutes")).intValue());
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void logsAreJsonAndTraceContextClearsAfterException(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var filter=new TraceFilter(new com.saas.backend.ops.OpsMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));var req=new org.springframework.mock.web.MockHttpServletRequest("GET","/test");
        var res=new org.springframework.mock.web.MockHttpServletResponse();
        assertThrows(jakarta.servlet.ServletException.class,()->filter.doFilter(req,res,(a,b)->{
            org.slf4j.MDC.put("tenantId",tenant.toString());
            org.slf4j.LoggerFactory.getLogger(getClass()).info("phase4-log quote\" newline\n");
            throw new jakarta.servlet.ServletException("test");
        }));
        assertNull(org.slf4j.MDC.get("traceId"));assertNull(org.slf4j.MDC.get("tenantId"));
        var line=output.getOut().lines().filter(l->l.startsWith("{") && l.contains("phase4-log")).findFirst().orElseThrow();
        var parsed=json.readTree(line);assertEquals(tenant.toString(),parsed.get("tenantId").asString());
        assertEquals(res.getHeader("X-Trace-Id"),parsed.get("traceId").asString());
    }
}
